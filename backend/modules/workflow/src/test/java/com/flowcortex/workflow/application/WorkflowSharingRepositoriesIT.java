package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.api.WorkflowVisibility;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowAccessLogEntry;
import com.flowcortex.workflow.infrastructure.WorkflowAccessLogRepository;
import com.flowcortex.workflow.infrastructure.WorkflowLinkGrantRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import java.io.File;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The sharing schema and queries against a real Postgres on the Flyway-migrated schema, so its
 * CHECKs, partial unique index and window unique key apply. Nothing runs in a test transaction:
 * the concurrency cases need each side to really commit.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=filesystem:${flowcortex.migrations.dir}",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class WorkflowSharingRepositoriesIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    private static final String MIGRATION_DIR = System.getProperty("flowcortex.migrations.dir");
    private static final String SHARING_MIGRATION = "2026.11.10.170";

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowLinkGrantRepository grantRepository;

    @Autowired
    private WorkflowAccessLogRepository accessLogRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private UUID insertWorkflow(UUID owner, String status, String visibility, String key, Instant keyExpiresAt,
            boolean deleted) {
        UUID id = UUID.randomUUID();
        Instant at = now();
        jdbc().update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at, status,"
                        + " deleted_at, visibility, unlisted_key, unlisted_key_generated_at, unlisted_key_expires_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, owner, "it-" + id, "public".equals(visibility), ts(at), ts(at), status,
                deleted ? ts(at) : null, visibility, key, key == null ? null : ts(at),
                keyExpiresAt == null ? null : ts(keyExpiresAt));
        return id;
    }

    private UUID insertUnlisted(UUID owner, String key) {
        return insertWorkflow(owner, "published", "unlisted", key, null, false);
    }

    private static String newKey() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 22);
    }

    private long grantCount(UUID workflowId, UUID userId) {
        return jdbc().queryForObject(
                "SELECT count(*) FROM workflow_link_grant WHERE workflow_id = ? AND user_id = ?", Long.class,
                workflowId, userId);
    }

    private boolean visible(UUID workflowId, UUID caller) {
        return workflowRepository.findVisibleTo(workflowId, caller).isPresent();
    }

    @Test
    void theMigrationMakesSharedRowsPublicAndOthersPrivate() {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        MigrationVersion before = lastVersionBefore(SHARING_MIGRATION);
        Flyway.configure().dataSource(dataSource).schemas(schema).locations("filesystem:" + MIGRATION_DIR)
                .target(before).load().migrate();
        UUID shared = UUID.randomUUID();
        UUID unshared = UUID.randomUUID();
        for (UUID id : List.of(shared, unshared)) {
            jdbc().update("INSERT INTO " + schema + ".workflow (id, owner_id, name, is_shared, created_at,"
                    + " updated_at, status) VALUES (?, ?, ?, ?, now(), now(), 'published')",
                    id, UUID.randomUUID(), "pre-" + id, id.equals(shared));
        }

        Flyway.configure().dataSource(dataSource).schemas(schema).locations("filesystem:" + MIGRATION_DIR)
                .load().migrate();

        assertThat(jdbc().queryForObject("SELECT visibility FROM " + schema + ".workflow WHERE id = ?",
                String.class, shared)).isEqualTo("public");
        assertThat(jdbc().queryForObject("SELECT visibility FROM " + schema + ".workflow WHERE id = ?",
                String.class, unshared)).isEqualTo("private");
    }

    private static MigrationVersion lastVersionBefore(String version) {
        MigrationVersion limit = MigrationVersion.fromVersion(version);
        MigrationVersion best = null;
        File[] files = new File(MIGRATION_DIR).listFiles((dir, name) -> name.matches("V[0-9.]+__.*\\.sql"));
        assertThat(files).isNotNull();
        for (File file : files) {
            String name = file.getName();
            MigrationVersion candidate = MigrationVersion.fromVersion(name.substring(1, name.indexOf("__")));
            if (candidate.compareTo(limit) < 0 && (best == null || candidate.compareTo(best) > 0)) {
                best = candidate;
            }
        }
        assertThat(best).as("a migration before %s", version).isNotNull();
        return best;
    }

    @Test
    void aNonOwnerSeesOnlyPublishedPublicOrGrantedUnlisted() {
        UUID owner = UUID.randomUUID();
        UUID ben = UUID.randomUUID();
        UUID publicDraft = insertWorkflow(owner, "draft", "public", null, null, false);
        UUID publishedPublic = insertWorkflow(owner, "published", "public", null, null, false);
        UUID publishedPrivate = insertWorkflow(owner, "published", "private", null, null, false);
        UUID unlistedNoGrant = insertUnlisted(owner, newKey());
        UUID unlistedGranted = insertUnlisted(owner, newKey());
        UUID unlistedDraftGranted = insertWorkflow(owner, "draft", "unlisted", newKey(), null, false);
        UUID deletedPublic = insertWorkflow(owner, "published", "public", null, null, true);
        UUID privateDraft = insertWorkflow(owner, "draft", "private", null, null, false);
        jdbc().update("INSERT INTO workflow_link_grant (workflow_id, user_id, created_at) VALUES (?, ?, now())",
                unlistedGranted, ben);
        jdbc().update("INSERT INTO workflow_link_grant (workflow_id, user_id, created_at) VALUES (?, ?, now())",
                unlistedDraftGranted, ben);

        assertThat(visible(publishedPublic, ben)).isTrue();
        assertThat(visible(unlistedGranted, ben)).isTrue();
        assertThat(visible(publicDraft, ben)).isFalse();
        assertThat(visible(publishedPrivate, ben)).isFalse();
        assertThat(visible(unlistedNoGrant, ben)).isFalse();
        assertThat(visible(unlistedDraftGranted, ben)).isFalse();
        assertThat(visible(deletedPublic, ben)).isFalse();
        assertThat(visible(privateDraft, ben)).isFalse();

        for (UUID id : List.of(publicDraft, publishedPublic, publishedPrivate, unlistedNoGrant, unlistedGranted,
                unlistedDraftGranted, privateDraft)) {
            assertThat(visible(id, owner)).as("owner sees %s", id).isTrue();
        }
        assertThat(visible(deletedPublic, owner)).isFalse();
    }

    @Test
    void theListHoldsOwnWorkflowsAndPublishedPublicOnly() {
        UUID owner = UUID.randomUUID();
        UUID ben = UUID.randomUUID();
        UUID publishedPublic = insertWorkflow(owner, "published", "public", null, null, false);
        UUID publicDraft = insertWorkflow(owner, "draft", "public", null, null, false);
        UUID privatePublished = insertWorkflow(owner, "published", "private", null, null, false);
        UUID unlistedGranted = insertUnlisted(owner, newKey());
        UUID bensOwn = insertWorkflow(ben, "draft", "private", null, null, false);
        jdbc().update("INSERT INTO workflow_link_grant (workflow_id, user_id, created_at) VALUES (?, ?, now())",
                unlistedGranted, ben);

        Set<UUID> bensList = new HashSet<>();
        workflowRepository.findAllVisibleTo(ben).forEach(w -> bensList.add(w.getId()));

        assertThat(bensList).contains(publishedPublic, bensOwn)
                .doesNotContain(publicDraft, privatePublished, unlistedGranted);
    }

    @Test
    void twoConcurrentAdmissionsWriteOneGrant() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ben = UUID.randomUUID();
        String key = newKey();
        UUID workflow = insertUnlisted(owner, key);
        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> admissions = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                admissions.add(pool.submit(() -> {
                    together.await(10, TimeUnit.SECONDS);
                    return tx().execute(status -> grantRepository.admit(workflow, ben, key, now()));
                }));
            }
            int written = 0;
            for (Future<Integer> admission : admissions) {
                written += admission.get(20, TimeUnit.SECONDS);
            }

            assertThat(written).isEqualTo(1);
            assertThat(grantCount(workflow, ben)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anAdmissionAfterASwitchToPrivateCommittedWritesNoGrant() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ben = UUID.randomUUID();
        String key = newKey();
        UUID workflow = insertUnlisted(owner, key);

        int written = admitWhileAnotherTransactionChanges(workflow, ben, key,
                w -> w.makeListedOrPrivate(WorkflowVisibility.PRIVATE));

        assertThat(written).isZero();
        assertThat(grantCount(workflow, ben)).isZero();
    }

    @Test
    void anAdmissionWithARotatedKeyWritesNoGrant() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ben = UUID.randomUUID();
        String key = newKey();
        UUID workflow = insertUnlisted(owner, key);

        int written = admitWhileAnotherTransactionChanges(workflow, ben, key, w -> w.rotateKey(newKey(), now()));

        assertThat(written).isZero();
        assertThat(grantCount(workflow, ben)).isZero();
    }

    /**
     * Transaction A takes the workflow's write lock; the admission then starts and must wait on
     * it; A commits the change; the admission resolves against the committed row.
     */
    private int admitWhileAnotherTransactionChanges(UUID workflow, UUID user, String key, Consumer<Workflow> change)
            throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> changer = pool.submit(() -> tx().executeWithoutResult(status -> {
                Workflow row = workflowRepository.findByIdAndDeletedAtIsNull(workflow).orElseThrow();
                locked.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                change.accept(row);
                workflowRepository.saveAndFlush(row);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> admission = pool.submit(
                    () -> tx().execute(status -> grantRepository.admit(workflow, user, key, now())));
            Thread.sleep(500);
            assertThat(admission.isDone()).as("the admission waits on the workflow row lock").isFalse();
            release.countDown();
            changer.get(20, TimeUnit.SECONDS);
            return admission.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anExpiredKeyAdmitsNobody() {
        UUID owner = UUID.randomUUID();
        UUID ben = UUID.randomUUID();
        String key = newKey();
        UUID workflow = insertWorkflow(owner, "published", "unlisted", key, now().minusSeconds(60), false);

        int written = tx().execute(status -> grantRepository.admit(workflow, ben, key, now()));

        assertThat(written).isZero();
        assertThat(grantCount(workflow, ben)).isZero();
    }

    @Test
    void concurrentReadsInOneWindowWriteOneAccessLogRow() throws Exception {
        UUID workflow = insertUnlisted(UUID.randomUUID(), newKey());
        Instant window = Instant.ofEpochSecond(Instant.now().getEpochSecond() / 600 * 600);
        int readers = 8;
        CyclicBarrier together = new CyclicBarrier(readers);
        ExecutorService pool = Executors.newFixedThreadPool(readers);
        try {
            List<Future<Integer>> inserts = new ArrayList<>();
            for (int i = 0; i < readers; i++) {
                inserts.add(pool.submit(() -> {
                    together.await(10, TimeUnit.SECONDS);
                    return tx().execute(status -> accessLogRepository.insertOnce(UUID.randomUUID(), workflow, null,
                            "203.0.x.x", "agent", "n:same", window, now()));
                }));
            }
            int written = 0;
            for (Future<Integer> insert : inserts) {
                written += insert.get(20, TimeUnit.SECONDS);
            }

            assertThat(written).isEqualTo(1);
            assertThat(logRows(workflow)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aReadInTheNextWindowWritesAnother() {
        UUID workflow = insertUnlisted(UUID.randomUUID(), newKey());
        UUID user = UUID.randomUUID();
        Instant window = Instant.ofEpochSecond(Instant.now().getEpochSecond() / 600 * 600);

        int first = tx().execute(status -> accessLogRepository.insertOnce(UUID.randomUUID(), workflow, user,
                null, "agent", "u:" + user, window, now()));
        int again = tx().execute(status -> accessLogRepository.insertOnce(UUID.randomUUID(), workflow, user,
                null, "agent", "u:" + user, window, now()));
        int next = tx().execute(status -> accessLogRepository.insertOnce(UUID.randomUUID(), workflow, user,
                null, "agent", "u:" + user, window.plusSeconds(600), now()));

        assertThat(first).isEqualTo(1);
        assertThat(again).isZero();
        assertThat(next).isEqualTo(1);
        assertThat(logRows(workflow)).isEqualTo(2);
    }

    @Test
    void aPageBoundaryInsideOneTimestampLosesNoRow() {
        UUID workflow = insertUnlisted(UUID.randomUUID(), newKey());
        Instant window = Instant.ofEpochSecond(Instant.now().getEpochSecond() / 600 * 600);
        Instant sameTime = now();
        Set<UUID> inserted = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            UUID id = UUID.randomUUID();
            inserted.add(id);
            int written = tx().execute(status -> accessLogRepository.insertOnce(id, workflow, null, null, "agent",
                    "n:" + id, window, sameTime));
            assertThat(written).isEqualTo(1);
        }

        List<WorkflowAccessLogEntry> first = accessLogRepository.firstPage(workflow, 2);
        WorkflowAccessLogEntry last = first.get(first.size() - 1);
        List<WorkflowAccessLogEntry> second = accessLogRepository.page(workflow, last.getCreatedAt(), last.getId(), 2);

        Set<UUID> seen = new HashSet<>();
        first.forEach(entry -> seen.add(entry.getId()));
        second.forEach(entry -> seen.add(entry.getId()));
        assertThat(first).hasSize(2);
        assertThat(second).hasSize(1);
        assertThat(seen).isEqualTo(inserted);
    }

    private long logRows(UUID workflow) {
        return jdbc().queryForObject("SELECT count(*) FROM workflow_access_log WHERE workflow_id = ?", Long.class,
                workflow);
    }
}
