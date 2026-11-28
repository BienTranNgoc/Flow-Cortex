package com.flowcortex.cvcheck.application;

import com.flowcortex.cvcheck.application.CvCheckService.Refusal;
import com.flowcortex.cvcheck.infrastructure.CvJobPostingRepository;
import com.flowcortex.cvcheck.infrastructure.JobPosting;
import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.llm.api.LlmSlots;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class CvJobRecommendationService {

    private static final int MAX_CACHE_ENTRIES = 200;
    private static final int ROLE_POOL_LIMIT = 30;
    private static final int CANDIDATE_POOL_SIZE = 15;
    private static final int TOP_RESULTS = 6;

    private static final String RANK_PROMPT = """
            You are a technical career advisor in Vietnam. Given a candidate profile and a numbered list of real open
            job postings in Vietnam, pick the 5 to 6 best-matching jobs (by index) and evaluate each one.
            Rules:
            - Only pick indices that exist in the CANDIDATE JOBS list; never invent jobs or indices.
            - Score matchScore (0-100) honestly based on role, seniority, and skill overlap.
            - matchedSkills: list 2-5 concrete skills from the candidate that match the job.
            - summary: write 1-2 concise sentences strictly in %s explaining why this job fits.

            CANDIDATE PROFILE:
            - Target Role: %s
            - Seniority: %s
            - Top Skills: %s
            - Location: %s

            CANDIDATE JOBS:
            %s
            """;

    private static final String RANK_SCHEMA = """
            {"type":"object","required":["picks"],"properties":{
            "picks":{"type":"array","items":{"type":"object",
            "required":["index","matchScore","matchedSkills","summary"],
            "properties":{
            "index":{"type":"integer"},
            "matchScore":{"type":"integer"},
            "matchedSkills":{"type":"array","items":{"type":"string"}},
            "summary":{"type":"string"}}}}}}
            """;

    private final LlmSlots llm;
    private final CvJobPostingRepository repository;
    private final CvJobFeedSyncJob syncJob;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, CachedEntry> memoryCache = new ConcurrentHashMap<>();

    CvJobRecommendationService(LlmSlots llm, CvJobPostingRepository repository, CvJobFeedSyncJob syncJob) {
        this.llm = llm;
        this.repository = repository;
        this.syncJob = syncJob;
    }

    public Outcome recommend(String primaryRole, String seniority, List<String> topSkills, String location) {
        return recommend(primaryRole, seniority, topSkills, location, "vi");
    }

    public Outcome recommend(String primaryRole, String seniority, List<String> topSkills, String location, String lang) {
        boolean en = CvDeterministicAuditor.isEnglish(lang);
        String langCode = en ? "en" : "vi";
        String role = primaryRole.strip();
        String level = (seniority == null || seniority.isBlank()) ? "Middle" : seniority.strip();
        String loc = (location == null || location.isBlank()) ? "Vietnam" : location.strip();
        List<String> skills = topSkills == null ? List.of() : topSkills.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::strip)
                .limit(6)
                .toList();

        String roleKey = normalizeRoleKey(role);
        String cacheKey = roleKey + "|" + langCode;
        Instant now = Instant.now();
        Instant freshSince = now.minus(syncJob.refreshWindow());

        CachedEntry memHit = memoryCache.get(cacheKey);
        if (memHit != null && memHit.expiresAt().isAfter(now)) {
            ObjectNode copy = memHit.payload().deepCopy();
            copy.put("cached", true);
            return new Outcome.Found(copy);
        }

        boolean servedFromDb = true;
        List<JobPosting> roleJobs = repository.findFreshByRoleKey(roleKey, freshSince, ROLE_POOL_LIMIT);
        Optional<Instant> syncedAt = repository.lastSyncedAtForRole(roleKey);

        if (roleJobs.isEmpty()) {
            servedFromDb = false;
            roleJobs = syncJob.fetchAndStoreForRole(roleKey, role, loc);
            syncedAt = roleJobs.isEmpty() ? Optional.empty() : Optional.of(now);
        }

        if (roleJobs.isEmpty()) {
            ObjectNode empty = buildBasePayload(role, skills, loc, syncedAt, false, syncJob.providerConfigured());
            return new Outcome.Found(empty);
        }

        List<ScoredJob> candidates = preRank(roleJobs, role, level, skills, loc);
        final Optional<Instant> effectiveSynced = syncedAt;
        final boolean isCached = servedFromDb;
        ObjectNode payload = rankWithLlm(candidates, role, level, skills, loc, effectiveSynced, isCached, en)
                .orElseGet(() -> fallbackPayload(candidates, role, skills, loc, effectiveSynced, isCached, en));

        Instant expiresAt = effectiveSynced.orElse(now).plus(syncJob.refreshWindow());
        putCache(cacheKey, payload, expiresAt, now);
        return new Outcome.Found(payload);
    }

    private Optional<ObjectNode> rankWithLlm(
            List<ScoredJob> candidates,
            String role,
            String level,
            List<String> skills,
            String location,
            Optional<Instant> syncedAt,
            boolean cached,
            boolean en) {
        StringBuilder jobList = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            JobPosting job = candidates.get(i).job();
            jobList.append('[').append(i).append("] ")
                    .append(job.title()).append(" @ ").append(job.company())
                    .append(" | ").append(job.location());
            if (!job.workMode().isBlank()) {
                jobList.append(" | ").append(job.workMode());
            }
            if (!job.salary().isBlank()) {
                jobList.append(" | ").append(job.salary());
            }
            if (!job.description().isBlank()) {
                String snippet = job.description().length() > 220
                        ? job.description().substring(0, 220) + "..."
                        : job.description();
                jobList.append(" | ").append(snippet);
            }
            jobList.append('\n');
        }

        String skillsText = skills.isEmpty() ? role : String.join(", ", skills);
        String prompt = RANK_PROMPT.formatted(en ? "English" : "Vietnamese", role, level, skillsText, location, jobList);
        LlmJsonResult result = llm.generateJson(CvCheckService.SLOT, prompt, RANK_SCHEMA);
        if (!(result instanceof LlmJsonResult.Answered answered)) {
            return Optional.empty();
        }

        try {
            JsonNode root = json.readTree(answered.json());
            JsonNode picks = root.path("picks");
            if (!picks.isArray() || picks.isEmpty()) {
                return Optional.empty();
            }

            ObjectNode payload = buildBasePayload(role, skills, location, syncedAt, cached, syncJob.providerConfigured());
            ArrayNode jobs = (ArrayNode) payload.get("jobs");
            Set<Integer> seen = new LinkedHashSet<>();

            for (JsonNode pick : picks) {
                int idx = pick.path("index").asInt(-1);
                if (idx < 0 || idx >= candidates.size() || !seen.add(idx)) {
                    continue;
                }
                JobPosting posting = candidates.get(idx).job();
                int score = Math.max(0, Math.min(100, pick.path("matchScore").asInt(75)));
                List<String> matched = new ArrayList<>();
                for (JsonNode sk : pick.path("matchedSkills")) {
                    if (sk.isString() && !sk.asString().isBlank()) {
                        matched.add(sk.asString().strip());
                    }
                }
                String summary = pick.path("summary").asString("").strip();
                appendJobNode(jobs, posting, score, matched, summary);
                if (jobs.size() >= TOP_RESULTS) {
                    break;
                }
            }
            return jobs.isEmpty() ? Optional.empty() : Optional.of(payload);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private ObjectNode fallbackPayload(
            List<ScoredJob> candidates,
            String role,
            List<String> skills,
            String location,
            Optional<Instant> syncedAt,
            boolean cached,
            boolean en) {
        ObjectNode payload = buildBasePayload(role, skills, location, syncedAt, cached, syncJob.providerConfigured());
        ArrayNode jobs = (ArrayNode) payload.get("jobs");
        for (int i = 0; i < Math.min(TOP_RESULTS, candidates.size()); i++) {
            ScoredJob scored = candidates.get(i);
            JobPosting posting = scored.job();
            int pct = Math.min(92, Math.max(60, 60 + scored.score() * 4));
            String defaultSummary = en
                    ? posting.title() + " at " + posting.company() + " (" + posting.location() + ")."
                    : "Vị trí " + posting.title() + " tại " + posting.company() + " (" + posting.location() + ").";
            String summary = posting.description().isBlank()
                    ? defaultSummary
                    : posting.description().length() > 160
                            ? posting.description().substring(0, 160) + "..."
                            : posting.description();
            appendJobNode(jobs, posting, pct, scored.matchedSkills(), summary);
        }
        return payload;
    }

    private void appendJobNode(ArrayNode jobs, JobPosting posting, int matchScore, List<String> matchedSkills,
            String summary) {
        ObjectNode node = jobs.addObject();
        node.put("title", posting.title());
        node.put("company", posting.company());
        node.put("location", posting.location());
        node.put("salary", posting.salary());
        node.put("workMode", posting.workMode());
        node.put("matchScore", matchScore);
        ArrayNode skNode = node.putArray("matchedSkills");
        for (String sk : matchedSkills) {
            skNode.add(sk);
        }
        node.put("summary", summary);
        node.put("source", posting.source());
        node.put("applyUrl", posting.applyUrl());
    }

    private List<ScoredJob> preRank(List<JobPosting> postings, String role, String level, List<String> skills,
            String location) {
        List<String> roleTokens = tokenize(role);
        String levelLower = level.toLowerCase(Locale.ROOT);
        List<String> lowerSkills = skills.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        List<String> locationTokens = tokenize(location);

        return postings.stream()
                .map(job -> {
                    String haystack = (job.title() + " " + String.join(" ", job.tags()) + " " + job.description())
                            .toLowerCase(Locale.ROOT);
                    String titleLower = job.title().toLowerCase(Locale.ROOT);
                    String jobLocLower = job.location().toLowerCase(Locale.ROOT);
                    int score = 0;
                    for (String token : roleTokens) {
                        if (titleLower.contains(token)) {
                            score += 4;
                        } else if (haystack.contains(token)) {
                            score += 1;
                        }
                    }
                    if (!levelLower.isBlank() && titleLower.contains(levelLower)) {
                        score += 3;
                    }
                    for (String locTok : locationTokens) {
                        if (jobLocLower.contains(locTok)) {
                            score += 2;
                        }
                    }
                    List<String> matched = new ArrayList<>();
                    for (int i = 0; i < lowerSkills.size(); i++) {
                        if (haystack.contains(lowerSkills.get(i))) {
                            score += 3;
                            matched.add(skills.get(i));
                        }
                    }
                    return new ScoredJob(job, score, matched);
                })
                .sorted(Comparator.comparingInt(ScoredJob::score).reversed())
                .limit(CANDIDATE_POOL_SIZE)
                .toList();
    }

    static String normalizeRoleKey(String role) {
        return role.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+#.\\s]+", " ")
                .replaceAll("\\s+", " ").strip();
    }

    private static List<String> tokenize(String text) {
        return List.of(text.toLowerCase(Locale.ROOT).split("[^a-z0-9+#.]+")).stream()
                .filter(t -> t.length() >= 2)
                .toList();
    }

    private ObjectNode buildBasePayload(String role, List<String> skills, String location,
            Optional<Instant> syncedAt, boolean cached, boolean configured) {
        ObjectNode payload = json.createObjectNode();
        payload.putArray("jobs");
        payload.set("searchLinks", buildSearchLinks(role, skills, location));
        if (syncedAt.isPresent()) {
            payload.put("lastSyncedAt", syncedAt.get().toString());
        } else {
            payload.putNull("lastSyncedAt");
        }
        payload.put("cached", cached);
        payload.put("configured", configured);
        return payload;
    }

    private ArrayNode buildSearchLinks(String role, List<String> skills, String location) {
        String query = skills.isEmpty() ? role : role + " " + skills.getFirst();
        String encodedQuery = encode(query);
        String encodedRole = encode(role);
        String encodedLocation = encode(location);
        String slug = role.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");

        ArrayNode links = json.createArrayNode();
        addLink(links, "ITviec", "https://itviec.com/it-jobs/" + (slug.isBlank() ? encodedRole : slug));
        addLink(links, "TopCV", "https://www.topcv.vn/tim-viec-lam-" + (slug.isBlank() ? encodedRole : slug));
        addLink(links, "LinkedIn",
                "https://www.linkedin.com/jobs/search/?keywords=" + encodedQuery + "&location=" + encodedLocation);
        addLink(links, "TopDev", "https://topdev.vn/jobs/search?keyword=" + encodedRole);
        return links;
    }

    private static void addLink(ArrayNode links, String label, String url) {
        ObjectNode node = links.addObject();
        node.put("label", label);
        node.put("url", url);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void putCache(String roleKey, ObjectNode payload, Instant expiresAt, Instant now) {
        if (memoryCache.size() >= MAX_CACHE_ENTRIES) {
            memoryCache.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(now));
            if (memoryCache.size() >= MAX_CACHE_ENTRIES) {
                memoryCache.clear();
            }
        }
        memoryCache.put(roleKey, new CachedEntry(payload.deepCopy(), expiresAt));
    }

    private record ScoredJob(JobPosting job, int score, List<String> matchedSkills) {
    }

    private record CachedEntry(ObjectNode payload, Instant expiresAt) {
    }

    public sealed interface Outcome permits Outcome.Found, Outcome.Refused {

        record Found(JsonNode payload) implements Outcome {
        }

        record Refused(Refusal reason) implements Outcome {
        }
    }
}
