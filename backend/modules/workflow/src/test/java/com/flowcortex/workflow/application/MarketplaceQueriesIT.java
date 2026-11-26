package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.application.MarketplaceQuery.Browse;
import com.flowcortex.workflow.application.MarketplaceQuery.Sort;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.infrastructure.MarketplaceQueries;
import com.flowcortex.workflow.infrastructure.MarketplaceQueries.CatalogueRow;
import com.flowcortex.workflow.infrastructure.MarketplaceQueries.Facet;
import com.flowcortex.workflow.infrastructure.MarketplaceQueries.Stats;
import com.flowcortex.workflow.infrastructure.WorkflowBookmarkRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The catalogue reads against a real Postgres on the Flyway-migrated schema. Every test runs in a transaction that
 * rolls back, and starts by soft-deleting the seeded workflows so the catalogue holds only its own rows.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=filesystem:${flowcortex.migrations.dir}",
        "spring.flyway.postgresql.transactional-lock=false",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Import(MarketplaceQueries.class)
@Testcontainers
class MarketplaceQueriesIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    private static final String PRIVATE = "private";
    private static final String PUBLIC = "public";
    private static final String UNLISTED = "unlisted";
    private static final String PUBLISHED = "published";
    private static final String DRAFT = "draft";

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;

    @Autowired
    private MarketplaceQueries queries;

    @Autowired
    private WorkflowBookmarkRepository bookmarkRepository;

    private JdbcTemplate jdbc;

    @BeforeEach
    void emptyTheCatalogue() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE workflow SET deleted_at = now() WHERE deleted_at IS NULL");
    }

    // ---- fixtures ----

    private static Timestamp at(Instant instant) {
        return Timestamp.from(instant);
    }

    private UUID insertWorkflow(UUID owner, String name, String status, String visibility, boolean deleted,
            Instant createdAt) {
        UUID id = UUID.randomUUID();
        String key = UNLISTED.equals(visibility) ? UUID.randomUUID().toString().replace("-", "").substring(0, 22)
                : null;
        jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at, status,"
                        + " deleted_at, visibility, unlisted_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, owner, name, PUBLIC.equals(visibility), at(createdAt), at(createdAt), status,
                deleted ? at(createdAt) : null, visibility, key);
        return id;
    }

    private void liveSteps(UUID workflow, String... types) {
        jdbc.update("DELETE FROM workflow_step WHERE workflow_id = ?", workflow);
        for (int i = 0; i < types.length; i++) {
            jdbc.update("INSERT INTO workflow_step (id, workflow_id, step_order, name, type)"
                    + " VALUES (?, ?, ?, ?, ?)", UUID.randomUUID(), workflow, i + 1, "s" + i, types[i]);
        }
    }

    /** Publishes version 1 whose snapshot holds {@code types} and points the workflow at it. */
    private void publish(UUID workflow, Instant publishedAt, String... types) {
        StringBuilder steps = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            steps.append(i == 0 ? "" : ",").append("{\"id\":\"").append(UUID.randomUUID())
                    .append("\",\"stepOrder\":").append(i + 1).append(",\"name\":\"s\",\"type\":\"")
                    .append(types[i]).append("\",\"config\":null,\"inputRef\":null}");
        }
        UUID version = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_version (id, workflow_id, version, source, snapshot, published_by,"
                        + " published_at) VALUES (?, ?, 1, 'publish', ?::jsonb, ?, ?)", version, workflow,
                "{\"name\":\"n\",\"steps\":[" + steps + "]}", UUID.randomUUID(), at(publishedAt));
        jdbc.update("UPDATE workflow SET published_version_id = ? WHERE id = ?", version, workflow);
    }

    /** A member-owned published public workflow whose live and published steps are {@code types}. */
    private UUID catalogue(UUID owner, String name, String... types) {
        UUID id = insertWorkflow(owner, name, PUBLISHED, PUBLIC, false, now.minus(10, ChronoUnit.DAYS));
        liveSteps(id, types);
        publish(id, now.minus(9, ChronoUnit.DAYS), types);
        return id;
    }

    private UUID catalogue(String name, String... types) {
        return catalogue(UUID.randomUUID(), name, types);
    }

    private UUID systemCatalogue(String name, Instant createdAt, String... types) {
        UUID id = insertWorkflow(Workflow.SYSTEM_OWNER_ID, name, PUBLISHED, PUBLIC, false, createdAt);
        liveSteps(id, types);
        return id;
    }

    private void stats(UUID workflow, int runners7d, int saves7d, int members30d, int trending) {
        jdbc.update("INSERT INTO workflow_discovery_stats (workflow_id, runners_7d, saves_7d, members_30d,"
                        + " trending_score, computed_at) VALUES (?, ?, ?, ?, ?, ?)", workflow, runners7d, saves7d,
                members30d, trending, at(now));
    }

    private void members(UUID workflow, int members30d) {
        stats(workflow, 0, 0, members30d, 0);
    }

    private void ratings(UUID workflow, int count, int sum) {
        jdbc.update("INSERT INTO workflow_rating_total (workflow_id, rating_count, rating_sum) VALUES (?, ?, ?)",
                workflow, count, sum);
    }

    private UUID run(UUID workflow, UUID owner, String status, Instant startedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, started_at)"
                + " VALUES (?, ?, ?, ?, ?)", id, workflow, owner, status, at(startedAt));
        return id;
    }

    private void childRun(UUID parentRun, UUID workflow, UUID owner, Instant startedAt) {
        UUID step = UUID.randomUUID();
        jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status) VALUES (?, ?, 1, 'echo', 'running')",
                step, parentRun);
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, started_at, parent_run_id,"
                        + " parent_step_id, parent_step_attempt, call_depth)"
                        + " VALUES (?, ?, ?, 'completed', ?, ?, ?, 1, 1)",
                UUID.randomUUID(), workflow, owner, at(startedAt), parentRun, step);
    }

    private void bookmark(UUID user, UUID workflow) {
        jdbc.update("INSERT INTO workflow_bookmark (user_id, workflow_id, created_at) VALUES (?, ?, now())",
                user, workflow);
    }

    private Instant daysAgo(int days) {
        return now.minus(days, ChronoUnit.DAYS);
    }

    // ---- reads ----

    private List<UUID> browse(String q, List<String> types, Sort sort) {
        return browse(queries, MarketplaceQuery.browse(q, types, sort, 0, 50));
    }

    private List<UUID> browse(Sort sort) {
        return browse(null, List.of(), sort);
    }

    private static List<UUID> browse(MarketplaceQueries source, Browse query) {
        return source.page(query).rows().stream().map(CatalogueRow::workflowId).toList();
    }

    private List<UUID> idsDescending(UUID... ids) {
        return jdbc.queryForList("SELECT id FROM workflow WHERE id IN (" + "?,".repeat(ids.length - 1) + "?)"
                + " ORDER BY id DESC", UUID.class, (Object[]) ids);
    }

    // ---- tests ----

    @Test
    void catalogueHoldsOnlyPublishedPublicNotDeleted() {
        UUID ana = UUID.randomUUID();
        UUID ben = UUID.randomUUID();
        UUID privateOne = insertWorkflow(ana, "private", PUBLISHED, PRIVATE, false, daysAgo(5));
        UUID unlisted = insertWorkflow(ana, "unlisted", PUBLISHED, UNLISTED, false, daysAgo(5));
        UUID draft = insertWorkflow(ana, "draft", DRAFT, PUBLIC, false, daysAgo(5));
        UUID deleted = insertWorkflow(ana, "deleted", PUBLISHED, PUBLIC, true, daysAgo(5));
        UUID published = catalogue(ana, "published", "tts");
        UUID callersOwn = catalogue(ben, "callers own", "translate");
        for (UUID hidden : List.of(privateOne, unlisted, draft, deleted)) {
            liveSteps(hidden, "probe");
        }

        assertThat(browse(Sort.NAME)).containsExactlyInAnyOrder(published, callersOwn);
        assertThat(queries.page(MarketplaceQuery.browse(null, List.of(), Sort.NAME, 0, 50)).total()).isEqualTo(2);
        assertThat(queries.facets()).extracting(Facet::type).containsExactlyInAnyOrder("tts", "translate");
        assertThat(queries.stats()).isEqualTo(new Stats(2, 2));
    }

    @Test
    void productVideoLeftOutWhileSwitchedOff() {
        jdbc.update("UPDATE workflow SET deleted_at = NULL, status = 'published', visibility = 'public',"
                + " is_shared = true WHERE id = ?", ProductVideoWorkflow.ID);
        UUID other = catalogue("other", "tts");

        MarketplaceQueries off = new MarketplaceQueries(namedJdbc, false);
        MarketplaceQueries on = new MarketplaceQueries(namedJdbc, true);
        Browse all = MarketplaceQuery.browse(null, List.of(), Sort.NAME, 0, 50);

        assertThat(browse(off, all)).containsExactly(other);
        assertThat(off.page(all).total()).isEqualTo(1);
        assertThat(off.stats().totalWorkflows()).isEqualTo(1);
        assertThat(browse(on, all)).containsExactlyInAnyOrder(other, ProductVideoWorkflow.ID);
    }

    @Test
    void searchIgnoresCaseAndAccents() {
        UUID video = catalogue("Tạo video đẹp", "tts");
        UUID voice = catalogue("Lồng tiếng", "tts");

        assertThat(browse("tao video", List.of(), Sort.NAME)).containsExactly(video);
        assertThat(browse("TẠO VIDEO", List.of(), Sort.NAME)).containsExactly(video);
        assertThat(browse("video tao", List.of(), Sort.NAME)).containsExactly(video);
        assertThat(browse("long tieng", List.of(), Sort.NAME)).containsExactly(voice);
        assertThat(browse("dep", List.of(), Sort.NAME)).containsExactly(video);
        assertThat(browse("Ta" + "̣" + "o", List.of(), Sort.NAME)).containsExactly(video);
        assertThat(browse("   ", List.of(), Sort.NAME)).containsExactlyInAnyOrder(video, voice);
        assertThat(browse("nothing like it", List.of(), Sort.NAME)).isEmpty();
    }

    @Test
    void wildcardsMatchLiterally() {
        UUID fifty = catalogue("Giảm 50% giá", "tts");
        UUID fiveHundred = catalogue("Giảm 500 giá", "tts");
        UUID underscore = catalogue("a_c name", "tts");
        catalogue("abc name", "tts");
        UUID backslash = catalogue("path a\\b", "tts");

        assertThat(browse("50%", List.of(), Sort.NAME)).containsExactly(fifty);
        assertThat(browse("a_c", List.of(), Sort.NAME)).containsExactly(underscore);
        assertThat(browse("a\\b", List.of(), Sort.NAME)).containsExactly(backslash);
        assertThat(browse("500", List.of(), Sort.NAME)).containsExactly(fiveHundred);
    }

    @Test
    void stepTypeFilterKeepsAnyListedType() {
        UUID translate = catalogue("A", "translate");
        UUID tts = catalogue("B", "tts");
        catalogue("C", "probe");

        assertThat(browse(null, List.of("translate", "tts"), Sort.NAME)).containsExactlyInAnyOrder(translate, tts);
        assertThat(browse(null, List.of("translate", "translate"), Sort.NAME)).containsExactly(translate);
    }

    @Test
    void stepTypeFilterReadsThePublishedDefinition() {
        UUID edited = catalogue("edited after publishing", "translate");
        liveSteps(edited, "tts");
        UUID system = systemCatalogue("seeded", daysAgo(3), "probe");
        UUID unpublished = insertWorkflow(UUID.randomUUID(), "no pointer", PUBLISHED, PUBLIC, false, daysAgo(2));
        liveSteps(unpublished, "echo");

        assertThat(browse(null, List.of("tts"), Sort.NAME)).isEmpty();
        assertThat(browse(null, List.of("translate"), Sort.NAME)).containsExactly(edited);
        assertThat(browse(null, List.of("probe"), Sort.NAME)).containsExactly(system);
        assertThat(browse(null, List.of("echo"), Sort.NAME)).containsExactly(unpublished);
    }

    @Test
    void everyOrderWithItsTieBreaks() {
        UUID w1 = catalogue("w1", "tts");
        UUID w2 = catalogue("w2", "tts");
        stats(w1, 4, 1, 4, 17);
        stats(w2, 2, 3, 2, 21);
        UUID fewerMembers = catalogue("fewer members", "tts");
        UUID moreMembers = catalogue("more members", "tts");
        stats(fewerMembers, 0, 0, 1, 5);
        stats(moreMembers, 0, 0, 2, 5);
        UUID older = insertWorkflow(UUID.randomUUID(), "older", PUBLISHED, PUBLIC, false, daysAgo(40));
        UUID newer = insertWorkflow(UUID.randomUUID(), "newer", PUBLISHED, PUBLIC, false, daysAgo(30));
        stats(older, 0, 0, 1, 4);
        stats(newer, 0, 0, 1, 4);
        UUID twinA = insertWorkflow(UUID.randomUUID(), "twin a", PUBLISHED, PUBLIC, false, daysAgo(50));
        UUID twinB = insertWorkflow(UUID.randomUUID(), "twin b", PUBLISHED, PUBLIC, false, daysAgo(50));
        stats(twinA, 0, 0, 1, 3);
        stats(twinB, 0, 0, 1, 3);

        List<UUID> trending = browse(Sort.TRENDING);

        assertThat(trending).startsWith(w2, w1, moreMembers, fewerMembers, newer, older);
        assertThat(trending.subList(6, 8)).isEqualTo(idsDescending(twinA, twinB));
        assertThat(browse(null, List.of(), null)).isEqualTo(trending);
    }

    @Test
    void popularOrdersByMembersThenTrending() {
        UUID busy = catalogue("busy", "tts");
        UUID hot = catalogue("hot", "tts");
        UUID calm = catalogue("calm", "tts");
        stats(busy, 0, 0, 9, 1);
        stats(hot, 0, 0, 3, 30);
        stats(calm, 0, 0, 3, 2);

        assertThat(browse(Sort.POPULAR)).containsExactly(busy, hot, calm);
    }

    @Test
    void newestOrdersByPublishTimeThenCreationTime() {
        UUID ana = UUID.randomUUID();
        UUID createdLateOldRelease = insertWorkflow(ana, "late created", PUBLISHED, PUBLIC, false, daysAgo(1));
        publish(createdLateOldRelease, daysAgo(3), "tts");
        UUID createdEarlyNewRelease = insertWorkflow(ana, "early created", PUBLISHED, PUBLIC, false, daysAgo(5));
        publish(createdEarlyNewRelease, daysAgo(1).minusSeconds(60), "tts");
        UUID seeded = systemCatalogue("seeded", daysAgo(2), "tts");

        assertThat(browse(Sort.NEWEST)).containsExactly(createdEarlyNewRelease, seeded, createdLateOldRelease);
    }

    @Test
    void topRatedOrdersByAverageThenCountUnratedLast() {
        UUID w1 = catalogue("w1", "tts");
        UUID w2 = catalogue("w2", "tts");
        UUID w3 = catalogue("w3 unrated", "tts");
        UUID w4 = catalogue("w4", "tts");
        ratings(w1, 2, 10);
        ratings(w2, 1, 5);
        ratings(w4, 2, 9);

        assertThat(browse(Sort.TOP_RATED)).containsExactly(w1, w2, w4, w3);
        assertThat(queries.page(MarketplaceQuery.browse(null, List.of(), Sort.TOP_RATED, 0, 50)).rows().getLast())
                .isEqualTo(new CatalogueRow(w3, 0, 0, 0));
    }

    @Test
    void nameOrdersWithoutCaseAndAccents() {
        UUID dep = catalogue("Đẹp", "tts");
        UUID banana = catalogue("banana", "tts");
        UUID apple = catalogue("Apple", "tts");
        UUID cherry = catalogue("cherry", "tts");

        assertThat(browse(Sort.NAME)).containsExactly(apple, banana, cherry, dep);
    }

    @Test
    void pagesCutTheOrderAndTotalCountsEveryMatch() {
        for (int i = 0; i < 5; i++) {
            catalogue("item " + i, "tts");
        }

        MarketplaceQueries.CataloguePage second = queries.page(MarketplaceQuery.browse(null, List.of(), Sort.NAME, 1, 2));
        MarketplaceQueries.CataloguePage past = queries.page(MarketplaceQuery.browse(null, List.of(), Sort.NAME, 9, 2));

        assertThat(second.rows()).hasSize(2);
        assertThat(second.total()).isEqualTo(5);
        assertThat(past.rows()).isEmpty();
        assertThat(past.total()).isEqualTo(5);
    }

    @Test
    void workflowWithoutFiguresCountsAsZero() {
        UUID bare = catalogue("bare", "tts");
        UUID known = catalogue("known", "tts");
        stats(known, 1, 0, 1, 3);

        List<CatalogueRow> rows = queries.page(MarketplaceQuery.browse(null, List.of(), Sort.TRENDING, 0, 50)).rows();

        assertThat(rows).containsExactly(new CatalogueRow(known, 1, 0, 0), new CatalogueRow(bare, 0, 0, 0));
    }

    @Test
    void recommendedPrefersAffinityThenFallsBack() {
        UUID ben = UUID.randomUUID();
        UUID c4 = catalogue("completed by ben", "tts");
        UUID c1 = catalogue("c1", "tts");
        UUID c2 = catalogue("c2", "translate");
        UUID c3 = catalogue(ben, "ben's own", "tts");
        for (UUID workflow : List.of(c1, c3, c4)) {
            members(workflow, 3);
        }
        members(c2, 9);
        run(c4, ben, "completed", daysAgo(5));

        assertThat(ids(queries.recommended(ben, 10, now, Set.of()))).containsExactly(c1);

        UUID noTts = UUID.randomUUID();
        run(catalogue("probe only", "probe"), noTts, "completed", daysAgo(5));
        List<UUID> fallback = ids(queries.recommended(noTts, 10, now, Set.of()));
        assertThat(fallback).first().isEqualTo(c2);
        assertThat(fallback).containsExactlyInAnyOrder(c2, c1, c3, c4);
    }

    @Test
    void recommendedFallsBackWhenAffinityIsOnlyOldOrApprovalCheckpoint() {
        UUID ben = UUID.randomUUID();
        UUID c1 = catalogue("c1", "tts");
        UUID c2 = catalogue("c2", "translate", "APPROVAL_CHECKPOINT");
        UUID unrated = catalogue("unrated twin", "echo");
        members(c1, 3);
        members(c2, 9);
        members(unrated, 3);
        ratings(c1, 1, 5);
        run(catalogue("tts long ago", "tts"), ben, "completed", daysAgo(100));
        run(catalogue("approval only", "APPROVAL_CHECKPOINT"), ben, "completed", daysAgo(5));

        assertThat(ids(queries.recommended(ben, 10, now, Set.of()))).containsExactly(c2, c1, unrated);
        assertThat(ids(queries.recommended(ben, 2, now, Set.of()))).containsExactly(c2, c1);
    }

    @Test
    void recommendedLeavesOutOwnCompletedAndExcluded() {
        UUID ben = UUID.randomUUID();
        UUID kept = catalogue("kept", "tts");
        UUID excluded = catalogue("excluded", "tts");
        UUID own = catalogue(ben, "own", "tts");
        UUID completed = catalogue("completed", "tts");
        UUID noMembers = catalogue("no members", "tts");
        UUID stillRunning = catalogue("still running", "tts");
        for (UUID workflow : List.of(kept, excluded, own, completed, stillRunning)) {
            members(workflow, 2);
        }
        stats(noMembers, 5, 5, 0, 40);
        run(completed, ben, "completed", daysAgo(200));
        run(stillRunning, ben, "running", daysAgo(1));

        assertThat(ids(queries.recommended(ben, 10, now, Set.of(excluded)))).containsExactlyInAnyOrder(stillRunning, kept);
    }

    @Test
    void similarRanksByCoRunnersAndSkipsPrivate() {
        UUID ana = UUID.randomUUID();
        UUID chi = UUID.randomUUID();
        UUID dan = UUID.randomUUID();
        UUID w = catalogue("w", "tts");
        UUID x = catalogue("x", "tts");
        UUID y = catalogue("y", "tts");
        UUID z = insertWorkflow(ana, "z", PUBLISHED, PRIVATE, false, daysAgo(5));
        UUID old = catalogue("old co-run", "tts");
        run(w, chi, "completed", daysAgo(3));
        run(w, dan, "running", daysAgo(4));
        run(x, chi, "completed", daysAgo(2));
        run(x, dan, "completed", daysAgo(2));
        run(y, dan, "completed", daysAgo(1));
        run(z, chi, "completed", daysAgo(1));
        run(old, chi, "completed", daysAgo(120));

        assertThat(ids(queries.similar(w, 8, now, Set.of()))).containsExactly(x, y);
        assertThat(ids(queries.similar(w, 1, now, Set.of()))).containsExactly(x);
        assertThat(queries.similar(UUID.randomUUID(), 8, now, Set.of())).isEmpty();
    }

    @Test
    void similarBreaksTiesByTrendingScore() {
        UUID chi = UUID.randomUUID();
        UUID w = catalogue("w", "tts");
        UUID cold = catalogue("cold", "tts");
        UUID warm = catalogue("warm", "tts");
        stats(warm, 1, 0, 1, 9);
        for (UUID workflow : List.of(w, cold, warm)) {
            run(workflow, chi, "completed", daysAgo(2));
        }

        assertThat(ids(queries.similar(w, 8, now, Set.of()))).containsExactly(warm, cold);
    }

    @Test
    void similarLeavesOutExcludedWorkflows() {
        UUID chi = UUID.randomUUID();
        UUID dan = UUID.randomUUID();
        UUID w = catalogue("w", "tts");
        UUID sample = catalogue("onboarding sample", "tts");
        UUID other = catalogue("other", "tts");
        for (UUID member : List.of(chi, dan)) {
            run(w, member, "completed", daysAgo(2));
            run(sample, member, "completed", daysAgo(2));
        }
        run(other, chi, "completed", daysAgo(1));

        assertThat(ids(queries.similar(w, 8, now, Set.of()))).containsExactly(sample, other);
        assertThat(ids(queries.similar(w, 8, now, Set.of(sample)))).containsExactly(other);
    }

    @Test
    void childRunsAreNotCoRuns() {
        UUID chi = UUID.randomUUID();
        UUID dan = UUID.randomUUID();
        UUID w = catalogue("w", "tts");
        UUID parent = catalogue("parent", "tts");
        UUID child = catalogue("child", "tts");
        UUID danOnly = catalogue("dan only", "tts");
        run(w, chi, "completed", daysAgo(3));
        UUID chiParentRun = run(parent, chi, "completed", daysAgo(2));
        childRun(chiParentRun, child, chi, daysAgo(2));
        UUID danParentRun = run(parent, dan, "completed", daysAgo(2));
        childRun(danParentRun, w, dan, daysAgo(2));
        run(danOnly, dan, "completed", daysAgo(2));

        assertThat(ids(queries.similar(w, 8, now, Set.of()))).containsExactly(parent);
    }

    @Test
    void facetsCountWorkflowsPerPublishedType() {
        UUID ana = UUID.randomUUID();
        catalogue(ana, "A", "translate", "tts", "tts");
        catalogue(ana, "B", "tts");
        UUID privateC = insertWorkflow(ana, "C", PUBLISHED, PRIVATE, false, daysAgo(5));
        liveSteps(privateC, "probe");
        UUID edited = catalogue(ana, "edited", "echo");
        liveSteps(edited, "fail");

        assertThat(queries.facets()).containsExactly(new Facet("tts", 2), new Facet("echo", 1),
                new Facet("translate", 1));
    }

    @Test
    void statsCountWorkflowsAndMemberOwners() {
        UUID ana = UUID.randomUUID();
        catalogue(ana, "a1", "tts");
        catalogue(ana, "a2", "tts");
        assertThat(queries.stats()).isEqualTo(new Stats(2, 1));

        catalogue(UUID.randomUUID(), "b1", "tts");
        assertThat(queries.stats()).isEqualTo(new Stats(3, 2));

        systemCatalogue("seeded", daysAgo(4), "tts");
        assertThat(queries.stats()).isEqualTo(new Stats(4, 2));
    }

    @Test
    void statsOfAnEmptyCatalogueAreZero() {
        assertThat(queries.stats()).isEqualTo(new Stats(0, 0));
        assertThat(queries.facets()).isEmpty();
    }

    @Test
    void countsMineIncludesDraftsAndSavedMatchesBookmarkedTotal() {
        UUID ben = UUID.randomUUID();
        UUID ana = UUID.randomUUID();
        insertWorkflow(ben, "draft", DRAFT, PRIVATE, false, daysAgo(3));
        insertWorkflow(ben, "published", PUBLISHED, PUBLIC, false, daysAgo(3));
        insertWorkflow(ben, "deleted", PUBLISHED, PUBLIC, true, daysAgo(3));
        UUID kept = catalogue(ana, "kept", "tts");
        UUID alsoKept = catalogue(ana, "also kept", "tts");
        UUID wentPrivate = catalogue(ana, "went private", "tts");
        for (UUID workflow : List.of(kept, alsoKept, wentPrivate)) {
            bookmark(ben, workflow);
        }
        jdbc.update("UPDATE workflow SET visibility = 'private', is_shared = false WHERE id = ?", wentPrivate);

        assertThat(queries.countMine(ben)).isEqualTo(2);
        assertThat(queries.countMine(UUID.randomUUID())).isZero();
        assertThat(bookmarkRepository.countVisible(ben)).isEqualTo(2);
    }

    @Test
    void bookmarkedAmongReturnsOnlyTheCallersSavedIds() {
        UUID ben = UUID.randomUUID();
        UUID saved = catalogue("saved", "tts");
        UUID notSaved = catalogue("not saved", "tts");
        UUID savedByOther = catalogue("saved by other", "tts");
        bookmark(ben, saved);
        bookmark(UUID.randomUUID(), savedByOther);

        assertThat(queries.bookmarkedAmong(ben, List.of(saved, notSaved, savedByOther))).containsExactly(saved);
        assertThat(queries.bookmarkedAmong(ben, List.of())).isEmpty();
    }

    private static List<UUID> ids(List<CatalogueRow> rows) {
        return rows.stream().map(CatalogueRow::workflowId).toList();
    }
}
