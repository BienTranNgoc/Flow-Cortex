package com.flowcortex.cvcheck.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class JobFeedClient {

    private static final Logger LOG = LoggerFactory.getLogger(JobFeedClient.class);

    private static final String SERPAPI_URL_TEMPLATE =
            "https://serpapi.com/search.json?engine=google_jobs&gl=vn&hl=vi&q=%s&location=%s&api_key=%s";
    private static final String JSEARCH_URL_TEMPLATE =
            "https://jsearch.p.rapidapi.com/search?query=%s&page=1&num_pages=1&country=vn&date_posted=month";

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_DESC_CHARS = 500;
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final String serpApiKey;
    private final String jSearchApiKey;

    @Autowired
    public JobFeedClient(
            @Value("${flowcortex.cvcheck.serpapi-api-key:}") String serpApiKey,
            @Value("${flowcortex.cvcheck.jsearch-api-key:}") String jSearchApiKey) {
        this(HttpClient.newBuilder()
                        .connectTimeout(TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .version(HttpClient.Version.HTTP_1_1)
                        .build(),
                serpApiKey, jSearchApiKey);
    }

    JobFeedClient() {
        this("", "");
    }

    JobFeedClient(HttpClient http, String serpApiKey, String jSearchApiKey) {
        this.http = http;
        this.serpApiKey = serpApiKey == null ? "" : serpApiKey.strip();
        this.jSearchApiKey = jSearchApiKey == null ? "" : jSearchApiKey.strip();
    }

    public boolean configured() {
        return !serpApiKey.isEmpty() || !jSearchApiKey.isEmpty();
    }

    public List<JobPosting> searchVietnamJobs(String roleQuery, String location) {
        String vnLocation = normalizeVietnamLocation(location);
        if (!serpApiKey.isEmpty()) {
            List<JobPosting> serpJobs = fetchSerpApi(roleQuery, vnLocation);
            if (!serpJobs.isEmpty()) {
                return serpJobs;
            }
        }
        if (!jSearchApiKey.isEmpty()) {
            return fetchJSearch(roleQuery + " " + vnLocation);
        }
        return List.of();
    }

    private List<JobPosting> fetchSerpApi(String query, String location) {
        try {
            String url = SERPAPI_URL_TEMPLATE.formatted(encode(query), encode(location), encode(serpApiKey));
            return parseSerpApi(get(url, Map.of()));
        } catch (Exception ex) {
            LOG.warn("cvcheck.job_feed source=serpapi query={} outcome=failed error={}",
                    query, ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private List<JobPosting> fetchJSearch(String query) {
        try {
            Map<String, String> headers = Map.of(
                    "X-RapidAPI-Key", jSearchApiKey,
                    "X-RapidAPI-Host", "jsearch.p.rapidapi.com");
            String url = JSEARCH_URL_TEMPLATE.formatted(encode(query));
            return parseJSearch(get(url, headers));
        } catch (Exception ex) {
            LOG.warn("cvcheck.job_feed source=jsearch query={} outcome=failed error={}",
                    query, ex.getClass().getSimpleName());
            return List.of();
        }
    }

    List<JobPosting> parseSerpApi(String body) {
        JsonNode root = json.readTree(body);
        List<JobPosting> jobs = new ArrayList<>();
        for (JsonNode item : root.path("jobs_results")) {
            String title = truncate(item.path("title").asString("").strip(), 300);
            String company = truncate(item.path("company_name").asString("").strip(), 200);
            if (title.isBlank() || company.isBlank()) {
                continue;
            }
            String externalId = item.path("job_id").asString("").strip();
            if (externalId.isBlank()) {
                externalId = Integer.toHexString((title + "|" + company).hashCode());
            }
            String url = extractSerpApplyUrl(item, title + " " + company);
            String location = truncate(item.path("location").asString("Vietnam").strip(), 200);
            String via = item.path("via").asString("").replaceFirst("(?i)^via\\s+", "").strip();
            String source = via.isBlank() ? "Google Jobs VN" : truncate(via, 32);
            JsonNode ext = item.path("detected_extensions");
            String salary = truncate(ext.path("salary").asString("").strip(), 120);
            String schedule = ext.path("schedule_type").asString(item.path("schedule_type").asString("")).strip();
            boolean wfh = ext.path("work_from_home").asBoolean(false);
            String workMode = wfh ? (schedule.isBlank() ? "Remote" : "Remote · " + schedule) : schedule;
            String desc = cleanDescription(item.path("description").asString(""));
            jobs.add(new JobPosting(source, truncate(externalId, 255), title, company, location, salary,
                    truncate(workMode, 64), List.of(), desc, url, Instant.now()));
        }
        return jobs;
    }

    List<JobPosting> parseJSearch(String body) {
        JsonNode root = json.readTree(body);
        List<JobPosting> jobs = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            String externalId = item.path("job_id").asString("").strip();
            String title = truncate(item.path("job_title").asString("").strip(), 300);
            String company = truncate(item.path("employer_name").asString("").strip(), 200);
            String url = safeUrl(item.path("job_apply_link").asString(""));
            if (url == null) {
                url = safeUrl(item.path("job_google_link").asString(""));
            }
            if (externalId.isBlank() || title.isBlank() || company.isBlank() || url == null) {
                continue;
            }
            String location = truncate(item.path("job_location").asString(
                    item.path("job_city").asString("Vietnam")).strip(), 200);
            String publisher = item.path("job_publisher").asString("").strip();
            String source = publisher.isBlank() ? "JSearch VN" : truncate(publisher, 32);
            String salary = formatJSearchSalary(item);
            boolean remote = item.path("job_is_remote").asBoolean(false);
            String empType = formatJobType(item.path("job_employment_type").asString(""));
            String workMode = remote ? (empType.isBlank() ? "Remote" : "Remote · " + empType)
                    : (empType.isBlank() ? "Onsite / Hybrid" : empType);
            String desc = cleanDescription(item.path("job_description").asString(""));
            long ts = item.path("job_posted_at_timestamp").asLong(0);
            Instant published = ts > 0 ? Instant.ofEpochSecond(ts)
                    : parseIsoDate(item.path("job_posted_at_datetime_utc").asString(""));
            jobs.add(new JobPosting(source, truncate(externalId, 255), title, company, location,
                    truncate(salary, 120), truncate(workMode, 64), List.of(), desc, url, published));
        }
        return jobs;
    }

    static String normalizeVietnamLocation(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Vietnam";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("ho chi minh") || lower.contains("hồ chí minh") || lower.contains("hcm")
                || lower.contains("saigon") || lower.contains("sài gòn")) {
            return "Ho Chi Minh City, Vietnam";
        }
        if (lower.contains("hanoi") || lower.contains("ha noi") || lower.contains("hà nội")) {
            return "Hanoi, Vietnam";
        }
        if (lower.contains("da nang") || lower.contains("đà nẵng")) {
            return "Da Nang, Vietnam";
        }
        return "Vietnam";
    }

    private String get(String url, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "FlowCortex-CvCheck/1.0")
                .GET();
        headers.forEach(builder::header);
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Unexpected HTTP status " + response.statusCode());
        }
        return response.body();
    }

    private static String extractSerpApplyUrl(JsonNode item, String fallbackQuery) {
        for (JsonNode option : item.path("apply_options")) {
            String candidate = safeUrl(option.path("link").asString(""));
            if (candidate != null) {
                return candidate;
            }
        }
        String share = safeUrl(item.path("share_link").asString(""));
        if (share != null) {
            return share;
        }
        return "https://www.google.com/search?q=" + encode(fallbackQuery + " tuyển dụng");
    }

    private static String formatJSearchSalary(JsonNode item) {
        JsonNode min = item.path("job_min_salary");
        JsonNode max = item.path("job_max_salary");
        if (!min.isNumber() && !max.isNumber()) {
            return "";
        }
        String currency = item.path("job_salary_currency").asString("USD").strip();
        String period = item.path("job_salary_period").asString("").strip();
        String range = min.isNumber() && max.isNumber()
                ? min.asLong() + " - " + max.asLong()
                : String.valueOf(min.isNumber() ? min.asLong() : max.asLong());
        return period.isBlank() ? range + " " + currency : range + " " + currency + "/" + period;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String cleanDescription(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = HTML_TAG.matcher(html).replaceAll(" ");
        text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        text = WHITESPACE.matcher(text).replaceAll(" ").strip();
        return truncate(text, MAX_DESC_CHARS);
    }

    private static String formatJobType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.replace('_', ' ').strip();
    }

    private static Instant parseIsoDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC);
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private static String safeUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(raw.strip());
            String scheme = uri.getScheme();
            if (("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) && uri.getHost() != null) {
                return raw.strip();
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
