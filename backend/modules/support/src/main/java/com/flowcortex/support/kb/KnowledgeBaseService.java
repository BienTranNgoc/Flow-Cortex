package com.flowcortex.support.kb;

import com.flowcortex.support.web.SupportProblemException;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The knowledge base: published reads for everyone, keyword search, and the admin's authoring of
 * articles and topic groups. At most three articles hold a "Bắt đầu" position (1–3).
 */
@Service
public class KnowledgeBaseService {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private static final int FEATURED_SLOTS = 3;

    private final TopicGroupRepository groups;
    private final ArticleRepository articles;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    KnowledgeBaseService(TopicGroupRepository groups, ArticleRepository articles, JdbcTemplate jdbcTemplate,
            Clock clock) {
        this.groups = groups;
        this.articles = articles;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /** A published article matched by a search, with its score: one per matched token, two when the title has it. */
    public record ScoredArticle(String slug, String title, String body, String groupName, int score) {
    }

    @Schema(name = "SupportKbArticleSummary")
    record ArticleSummary(
            @Schema(requiredMode = RequiredMode.REQUIRED) String slug,
            @Schema(requiredMode = RequiredMode.REQUIRED) String title) {
    }

    @Schema(name = "SupportKbGroup")
    record PublishedGroup(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = RequiredMode.REQUIRED) int position,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<ArticleSummary> articles) {
    }

    @Schema(name = "SupportKbArticle")
    record PublishedArticle(
            @Schema(requiredMode = RequiredMode.REQUIRED) String slug,
            @Schema(requiredMode = RequiredMode.REQUIRED) String title,
            @Schema(requiredMode = RequiredMode.REQUIRED) String body,
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID groupId,
            @Schema(requiredMode = RequiredMode.REQUIRED) String groupName,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant publishedAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {
    }

    @Schema(name = "SupportKbFeaturedArticle")
    record FeaturedArticle(
            @Schema(requiredMode = RequiredMode.REQUIRED) String slug,
            @Schema(requiredMode = RequiredMode.REQUIRED) String title,
            @Schema(requiredMode = RequiredMode.REQUIRED) int position) {
    }

    @Schema(name = "SupportKbSearchHit")
    record SearchHit(
            @Schema(requiredMode = RequiredMode.REQUIRED) String slug,
            @Schema(requiredMode = RequiredMode.REQUIRED) String title,
            @Schema(requiredMode = RequiredMode.REQUIRED) String groupName) {
    }

    @Schema(name = "AdminSupportKbGroup")
    record AdminGroup(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = RequiredMode.REQUIRED) int position,
            @Schema(requiredMode = RequiredMode.REQUIRED) long articleCount) {
    }

    @Schema(name = "AdminSupportKbArticleSummary")
    record AdminArticleSummary(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) String slug,
            @Schema(requiredMode = RequiredMode.REQUIRED) String title,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"draft", "published", "archived"})
            String status,
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID groupId,
            @Schema(requiredMode = RequiredMode.REQUIRED) String groupName,
            Integer featuredPosition,
            Instant publishedAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {
    }

    @Schema(name = "AdminSupportKbArticleList")
    record AdminArticleList(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<AdminArticleSummary> items,
            @Schema(requiredMode = RequiredMode.REQUIRED) long publishedFeaturedCount) {
    }

    @Schema(name = "AdminSupportKbArticle")
    record AdminArticle(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) String slug,
            @Schema(requiredMode = RequiredMode.REQUIRED) String title,
            @Schema(requiredMode = RequiredMode.REQUIRED) String body,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"draft", "published", "archived"})
            String status,
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID groupId,
            Integer featuredPosition,
            Instant publishedAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {
    }

    /** What the admin's editor saves; {@code featuredPosition} null means not a "Bắt đầu" article. */
    record ArticleDraft(UUID groupId, String slug, String title, String body, Integer featuredPosition) {
    }

    /** Groups in the admin's order, each with its published articles; a group with none is left out. */
    @Transactional(readOnly = true)
    List<PublishedGroup> publishedGroups() {
        Map<UUID, List<ArticleSummary>> byGroup = new HashMap<>();
        for (Article article : articles.findByStatusOrderByTitleAsc(Article.PUBLISHED)) {
            byGroup.computeIfAbsent(article.getGroupId(), id -> new ArrayList<>())
                    .add(new ArticleSummary(article.getSlug(), article.getTitle()));
        }
        return groups.findAllByOrderByPositionAscNameAsc().stream()
                .filter(group -> byGroup.containsKey(group.getId()))
                .map(group -> new PublishedGroup(group.getId(), group.getName(), group.getPosition(),
                        byGroup.get(group.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    List<FeaturedArticle> featured() {
        return articles.findByStatusAndFeaturedPositionNotNullOrderByFeaturedPositionAsc(Article.PUBLISHED).stream()
                .map(article -> new FeaturedArticle(article.getSlug(), article.getTitle(),
                        article.getFeaturedPosition()))
                .toList();
    }

    @Transactional(readOnly = true)
    PublishedArticle publishedArticle(String slug) {
        Article article = articles.findBySlug(slug).filter(Article::isPublished)
                .orElseThrow(() -> articleNotFound(slug));
        String groupName = groups.findById(article.getGroupId()).map(TopicGroup::getName).orElseThrow();
        return new PublishedArticle(article.getSlug(), article.getTitle(), article.getBody(), article.getGroupId(),
                groupName, article.getPublishedAt(), article.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    List<SearchHit> searchHits(String query, int limit) {
        return search(query, limit).stream()
                .map(hit -> new SearchHit(hit.slug(), hit.title(), hit.groupName()))
                .toList();
    }

    /**
     * Published articles holding at least one word (2+ characters) of {@code query}, ignoring case and
     * diacritics, best score first and at most {@code limit} of them.
     */
    @Transactional(readOnly = true)
    public List<ScoredArticle> search(String query, int limit) {
        List<String> tokens = SearchText.tokens(query);
        if (tokens.isEmpty() || limit < 1) {
            return List.of();
        }
        String anyToken = String.join(" OR ", Collections.nCopies(tokens.size(), "a.search_text ILIKE ?"));
        Object[] patterns = tokens.stream().map(token -> "%" + token + "%").toArray();
        List<ScoredArticle> matches = jdbcTemplate.query(
                "SELECT a.slug, a.title, a.body, a.search_text, g.name AS group_name FROM support_article a "
                        + "JOIN support_topic_group g ON g.id = a.group_id "
                        + "WHERE a.status = 'published' AND (" + anyToken + ")",
                (rs, rowNum) -> new ScoredArticle(rs.getString("slug"), rs.getString("title"), rs.getString("body"),
                        rs.getString("group_name"), score(tokens, rs.getString("title"), rs.getString("search_text"))),
                patterns);
        return matches.stream()
                .sorted(Comparator.comparingInt(ScoredArticle::score).reversed().thenComparing(ScoredArticle::title))
                .limit(limit)
                .toList();
    }

    static int score(List<String> tokens, String title, String searchText) {
        String normalizedTitle = SearchText.normalize(title);
        int score = 0;
        for (String token : tokens) {
            if (normalizedTitle.contains(token)) {
                score += 2;
            } else if (searchText.contains(token)) {
                score += 1;
            }
        }
        return score;
    }

    @Transactional(readOnly = true)
    List<AdminGroup> adminGroups() {
        Map<UUID, Long> counts = articleCounts();
        return groups.findAllByOrderByPositionAscNameAsc().stream()
                .map(group -> adminGroup(group, counts.getOrDefault(group.getId(), 0L)))
                .toList();
    }

    @Transactional
    AdminGroup createGroup(String name) {
        if (groups.existsByName(name)) {
            throw groupNameTaken(name);
        }
        TopicGroup group = new TopicGroup(name, groups.maxPosition() + 1, Instant.now(clock));
        saveGroup(group);
        LOG.info("support.kb outcome=group_created group={}", group.getId());
        return adminGroup(group, 0);
    }

    @Transactional
    AdminGroup renameGroup(UUID id, String name) {
        TopicGroup group = groups.findById(id).orElseThrow(() -> groupNotFound(id));
        if (groups.existsByNameAndIdNot(name, id)) {
            throw groupNameTaken(name);
        }
        group.rename(name, Instant.now(clock));
        saveGroup(group);
        LOG.info("support.kb outcome=group_renamed group={}", id);
        return adminGroup(group, articles.countByGroupId(id));
    }

    /** {@code orderedIds} must name every group exactly once; the first gets position 1. */
    @Transactional
    List<AdminGroup> reorderGroups(List<UUID> orderedIds) {
        Map<UUID, TopicGroup> all = groups.findAll().stream()
                .collect(Collectors.toMap(TopicGroup::getId, Function.identity()));
        Set<UUID> requested = new HashSet<>(orderedIds);
        if (requested.size() != orderedIds.size() || !requested.equals(all.keySet())) {
            throw new SupportProblemException(HttpStatus.BAD_REQUEST, "group-order-mismatch",
                    "The order must list every topic group exactly once");
        }
        Instant now = Instant.now(clock);
        for (int i = 0; i < orderedIds.size(); i++) {
            all.get(orderedIds.get(i)).moveTo(i + 1, now);
        }
        LOG.info("support.kb outcome=groups_reordered count={}", orderedIds.size());
        return adminGroups();
    }

    /** Refused while the group holds any article, archived ones included. */
    @Transactional
    void deleteGroup(UUID id) {
        TopicGroup group = groups.findById(id).orElseThrow(() -> groupNotFound(id));
        long held = articles.countByGroupId(id);
        if (held > 0) {
            throw groupNotEmpty(held);
        }
        try {
            groups.delete(group);
            groups.flush();
        } catch (DataIntegrityViolationException e) {
            // The failed flush aborted the transaction, so no recount: an article arrived since the check.
            throw groupNotEmpty(1);
        }
        LOG.info("support.kb outcome=group_deleted group={}", id);
    }

    @Transactional(readOnly = true)
    AdminArticleList adminArticles() {
        Map<UUID, String> groupNames = groups.findAll().stream()
                .collect(Collectors.toMap(TopicGroup::getId, TopicGroup::getName));
        List<AdminArticleSummary> items = articles.findAllByOrderByUpdatedAtDesc().stream()
                .map(article -> new AdminArticleSummary(article.getId(), article.getSlug(), article.getTitle(),
                        article.getStatus(), article.getGroupId(), groupNames.get(article.getGroupId()),
                        position(article), article.getPublishedAt(), article.getUpdatedAt()))
                .toList();
        return new AdminArticleList(items, articles.countByStatusAndFeaturedPositionNotNull(Article.PUBLISHED));
    }

    @Transactional(readOnly = true)
    AdminArticle adminArticle(UUID id) {
        return adminArticle(findArticle(id));
    }

    @Transactional
    AdminArticle createArticle(ArticleDraft draft) {
        requireGroup(draft.groupId());
        if (articles.existsBySlug(draft.slug())) {
            throw slugTaken(draft.slug());
        }
        Short position = featuredPosition(null, false, draft.featuredPosition());
        Article article = new Article(draft.groupId(), draft.slug(), draft.title(), draft.body(), position,
                Instant.now(clock));
        saveArticle(article);
        LOG.info("support.kb outcome=created slug={}", article.getSlug());
        return adminArticle(article);
    }

    @Transactional
    AdminArticle updateArticle(UUID id, ArticleDraft draft) {
        Article article = findArticle(id);
        requireGroup(draft.groupId());
        if (articles.existsBySlugAndIdNot(draft.slug(), id)) {
            throw slugTaken(draft.slug());
        }
        Short position = featuredPosition(article.getId(), article.isArchived(), draft.featuredPosition());
        article.edit(draft.groupId(), draft.slug(), draft.title(), draft.body(), position, Instant.now(clock));
        saveArticle(article);
        LOG.info("support.kb outcome=edited slug={}", article.getSlug());
        return adminArticle(article);
    }

    @Transactional
    AdminArticle publish(UUID id) {
        Article article = findArticle(id);
        article.publish(Instant.now(clock));
        LOG.info("support.kb outcome=published slug={}", article.getSlug());
        return adminArticle(article);
    }

    /** Archiving frees the article's "Bắt đầu" position. */
    @Transactional
    AdminArticle archive(UUID id) {
        Article article = findArticle(id);
        article.archive(Instant.now(clock));
        LOG.info("support.kb outcome=archived slug={}", article.getSlug());
        return adminArticle(article);
    }

    /**
     * The "Bắt đầu" position the article may take: null clears it; a position another article holds
     * is refused as {@code featured-full} when all three are taken, else {@code featured-position-taken}.
     */
    private Short featuredPosition(UUID articleId, boolean archived, Integer requested) {
        if (requested == null) {
            return null;
        }
        if (archived) {
            throw new SupportProblemException(HttpStatus.BAD_REQUEST, "validation-failed",
                    "An archived article cannot be a start article")
                    .with("errors", Map.of("featuredPosition", "must be empty for an archived article"));
        }
        List<Article> others = articles.findByFeaturedPositionNotNull().stream()
                .filter(other -> !other.getId().equals(articleId))
                .toList();
        boolean taken = others.stream().anyMatch(other -> other.getFeaturedPosition() == requested.shortValue());
        if (taken && others.size() >= FEATURED_SLOTS) {
            throw featuredFull();
        }
        if (taken) {
            throw new SupportProblemException(HttpStatus.CONFLICT, "featured-position-taken",
                    "Start position " + requested + " is held by another article");
        }
        return requested.shortValue();
    }

    private void saveArticle(Article article) {
        try {
            articles.saveAndFlush(article);
        } catch (DataIntegrityViolationException e) {
            String constraint = constraintName(e);
            if ("support_article_slug_key".equals(constraint)) {
                throw slugTaken(article.getSlug());
            }
            if ("uq_support_article_featured_position".equals(constraint)) {
                throw new SupportProblemException(HttpStatus.CONFLICT, "featured-position-taken",
                        "Start position " + article.getFeaturedPosition() + " is held by another article");
            }
            throw e;
        }
    }

    private void saveGroup(TopicGroup group) {
        try {
            groups.saveAndFlush(group);
        } catch (DataIntegrityViolationException e) {
            if ("support_topic_group_name_key".equals(constraintName(e))) {
                throw groupNameTaken(group.getName());
            }
            throw e;
        }
    }

    private static String constraintName(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return null;
    }

    private Map<UUID, Long> articleCounts() {
        Map<UUID, Long> counts = new LinkedHashMap<>();
        for (Object[] row : articles.countByGroup()) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }

    private void requireGroup(UUID groupId) {
        if (!groups.existsById(groupId)) {
            throw groupNotFound(groupId);
        }
    }

    private Article findArticle(UUID id) {
        return articles.findById(id).orElseThrow(() -> articleNotFound(id.toString()));
    }

    private static AdminGroup adminGroup(TopicGroup group, long articleCount) {
        return new AdminGroup(group.getId(), group.getName(), group.getPosition(), articleCount);
    }

    private static AdminArticle adminArticle(Article article) {
        return new AdminArticle(article.getId(), article.getSlug(), article.getTitle(), article.getBody(),
                article.getStatus(), article.getGroupId(), position(article), article.getPublishedAt(),
                article.getUpdatedAt());
    }

    private static Integer position(Article article) {
        return article.getFeaturedPosition() == null ? null : Integer.valueOf(article.getFeaturedPosition());
    }

    private static SupportProblemException articleNotFound(String ref) {
        return new SupportProblemException(HttpStatus.NOT_FOUND, "article-not-found",
                "Article not found: " + ref);
    }

    private static SupportProblemException groupNotFound(UUID id) {
        return new SupportProblemException(HttpStatus.NOT_FOUND, "group-not-found", "No topic group " + id);
    }

    private static SupportProblemException groupNotEmpty(long held) {
        return new SupportProblemException(HttpStatus.CONFLICT, "group-not-empty",
                "The topic group still holds " + held + " article(s)").with("articleCount", held);
    }

    private static SupportProblemException groupNameTaken(String name) {
        return new SupportProblemException(HttpStatus.CONFLICT, "group-name-taken",
                "A topic group named " + name + " already exists");
    }

    private static SupportProblemException slugTaken(String slug) {
        return new SupportProblemException(HttpStatus.CONFLICT, "slug-taken", "The slug " + slug + " is taken");
    }

    private static SupportProblemException featuredFull() {
        return new SupportProblemException(HttpStatus.CONFLICT, "featured-full",
                "Three articles already hold a start position");
    }
}
