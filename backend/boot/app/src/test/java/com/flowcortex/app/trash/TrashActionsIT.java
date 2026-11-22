package com.flowcortex.app.trash;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.trash.api.PurgeOutcome;
import com.flowcortex.trash.api.RestoreOutcome;
import com.flowcortex.trash.api.TrashBin;
import com.flowcortex.trash.api.TrashRecord;
import com.flowcortex.user.api.UserView;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Restore, purge and empty over HTTP against a real Postgres with the two scripted providers. Each test creates its
 * own accounts and entries; the entry table is emptied around every test.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.trash.enabled=true")
@AutoConfigureTestRestTemplate
@Import(TrashTestProviders.class)
class TrashActionsIT extends AbstractWorkflowHttpIT {

    private static final String RESTORE = "/api/v1/trash/restore";
    private static final String PURGE = "/api/v1/trash/purge";
    private static final String EMPTY = "/api/v1/trash/empty";
    private static final String LOCATION = "trash.location.demo";
    private static final Duration SEED_AGE = Duration.ofDays(1);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TrashBin bin;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TrashTestProviders.SettableClock clock;

    @Autowired
    private TrashTestProviders.CallLog callLog;

    @Autowired
    private TrashTestProviders.ScriptedProvider demoProvider;

    @Autowired
    private TrashTestProviders.ScriptedProvider demoLateProvider;

    private TransactionTemplate transaction;
    private UserView memberA;
    private UserView memberB;
    private String tokenA;
    private String tokenB;

    record Ref(String type, String id) {
    }

    record Skipped(String type, String id, String reason, String message) {
    }

    record BulkView(List<Ref> succeeded, List<Skipped> skipped) {
    }

    record EmptyView(long purged, long blocked, long failed, long remaining, String next) {
    }

    record ProblemView(int status, String detail, Map<String, String> errors) {
    }

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        clock.set(Instant.now());
        reset();
        memberA = newUser("trash-a");
        memberB = newUser("trash-b");
        tokenA = loginAs(memberA);
        tokenB = loginAs(memberB);
    }

    @AfterEach
    void tearDown() {
        dropEntryDeleteRefusal();
        reset();
        clock.set(Instant.now());
    }

    @Test
    void singleRestoreSucceedsAndRemovesTheEntry() {
        trash(demoProvider, "1", memberA);

        ResponseEntity<BulkView> answer = post(RESTORE, tokenA, items(ref("demo", "1")), BulkView.class);

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(answer.getBody().succeeded()).containsExactly(new Ref("demo", "1"));
        assertThat(answer.getBody().skipped()).isEmpty();
        assertThat(demoProvider.calls()).containsExactly("restore:1");
        assertThat(demoProvider.isDeleted("1")).isFalse();
        assertThat(entryExists("demo", "1")).isFalse();
    }

    @Test
    void conflictAnswers409WithTheProviderMessageAndKeepsTheEntry() {
        trash(demoProvider, "3", memberA);
        demoProvider.answerRestore("3", new RestoreOutcome.Conflict(TrashTestProviders.NAME_TAKEN));

        ResponseEntity<ProblemView> english = post(RESTORE, tokenA, items(ref("demo", "3")), ProblemView.class);
        ResponseEntity<ProblemView> vietnamese = postIn("vi", RESTORE, tokenA, items(ref("demo", "3")),
                ProblemView.class);

        assertThat(english.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(english.getBody().detail()).isEqualTo("The name is taken");
        assertThat(vietnamese.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(vietnamese.getBody().detail()).isEqualTo("Tên đã được dùng");
        assertThat(entryExists("demo", "3")).isTrue();
        assertThat(demoProvider.isDeleted("3")).isTrue();
    }

    @Test
    void aProviderMessageWithoutABundleKeyIsShownAsItsCode() {
        trash(demoProvider, "3", memberA);
        demoProvider.answerRestore("3", new RestoreOutcome.Conflict("demo.noSuchKey"));

        ResponseEntity<ProblemView> answer = post(RESTORE, tokenA, items(ref("demo", "3")), ProblemView.class);

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(answer.getBody().detail()).isEqualTo("demo.noSuchKey");
    }

    @Test
    void batchReportsEachItemInRequestOrder() {
        trash(demoProvider, "1", memberA);
        trash(demoProvider, "3", memberA);
        demoProvider.answerRestore("3", new RestoreOutcome.Conflict(TrashTestProviders.NAME_TAKEN));

        BulkView answer = post(RESTORE, tokenA, items(ref("demo", "1"), ref("demo", "999"), ref("demo", "3")),
                BulkView.class).getBody();

        assertThat(answer.succeeded()).containsExactly(new Ref("demo", "1"));
        assertThat(answer.skipped()).containsExactly(
                new Skipped("demo", "999", "notFound", null),
                new Skipped("demo", "3", "conflict", "The name is taken"));
        assertThat(entryExists("demo", "3")).isTrue();
        assertThat(demoProvider.calls()).containsExactly("restore:1", "restore:3");
    }

    @Test
    void goneAnswers404AndRemovesTheEntry() {
        trash(demoProvider, "4", memberA);
        demoProvider.answerRestore("4", new RestoreOutcome.Gone());

        ResponseEntity<ProblemView> answer = post(RESTORE, tokenA, items(ref("demo", "4")), ProblemView.class);

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(answer.getBody().detail()).isEqualTo("This item is not in your Trash");
        assertThat(entryExists("demo", "4")).isFalse();
    }

    @Test
    void providerThrowingInABatchIsFailedAndOthersProceed() {
        trash(demoProvider, "5", memberA);
        trash(demoProvider, "1", memberA);
        demoProvider.failRestore("5", new IllegalStateException("provider is down"));

        BulkView answer = post(RESTORE, tokenA, items(ref("demo", "5"), ref("demo", "1")), BulkView.class).getBody();

        assertThat(answer.skipped()).containsExactly(new Skipped("demo", "5", "failed", null));
        assertThat(answer.succeeded()).containsExactly(new Ref("demo", "1"));
        assertThat(entryExists("demo", "5")).isTrue();
        assertThat(entryExists("demo", "1")).isFalse();
    }

    @Test
    void providerThrowingOnASingleItemAnswers500AndKeepsTheEntry() {
        trash(demoProvider, "5", memberA);
        demoProvider.failRestore("5", new IllegalStateException("provider is down"));

        ResponseEntity<ProblemView> answer = post(RESTORE, tokenA, items(ref("demo", "5")), ProblemView.class);

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(entryExists("demo", "5")).isTrue();
    }

    @Test
    void anotherOwnersItemAnswersLikeAnUnknownOne() {
        trash(demoProvider, "1", memberA);

        ResponseEntity<ProblemView> others = post(RESTORE, tokenB, items(ref("demo", "1")), ProblemView.class);
        ResponseEntity<ProblemView> unknown = post(RESTORE, tokenB, items(ref("demo", "999")), ProblemView.class);

        assertThat(others.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(others.getBody()).isEqualTo(unknown.getBody());
        assertThat(others.getBody().detail()).isEqualTo("This item is not in your Trash");
        assertThat(entryExists("demo", "1")).isTrue();
        assertThat(demoProvider.calls()).isEmpty();
    }

    @Test
    void anotherOwnersItemInABatchIsSkippedAsNotFound() {
        trash(demoProvider, "1", memberA);

        BulkView answer = post(PURGE, tokenB, items(ref("demo", "1"), ref("demo", "999")), BulkView.class).getBody();

        assertThat(answer.succeeded()).isEmpty();
        assertThat(answer.skipped()).containsExactly(
                new Skipped("demo", "1", "notFound", null), new Skipped("demo", "999", "notFound", null));
        assertThat(entryExists("demo", "1")).isTrue();
        assertThat(callLog.entries()).isEmpty();
    }

    @Test
    void anEntryOfATypeWithoutAProviderIsNotFoundAndKept() {
        record("retired", "7", memberA);

        ResponseEntity<ProblemView> answer = post(PURGE, tokenA, items(ref("retired", "7")), ProblemView.class);

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(answer.getBody().detail()).isEqualTo("This item is not in your Trash");
        assertThat(entryExists("retired", "7")).isTrue();
    }

    @Test
    void processedByRankReportedByRequest() {
        trash(demoProvider, "1", memberA);
        trash(demoLateProvider, "2", memberA);

        BulkView answer = post(PURGE, tokenA, items(ref("demo_late", "2"), ref("demo", "1")), BulkView.class)
                .getBody();

        assertThat(callLog.entries()).containsExactly("demo:purge:1", "demo_late:purge:2");
        assertThat(answer.succeeded()).containsExactly(new Ref("demo_late", "2"), new Ref("demo", "1"));
        assertThat(entryExists("demo", "1")).isFalse();
        assertThat(entryExists("demo_late", "2")).isFalse();
    }

    @Test
    void blockedAnswers409AndKeepsTheEntry() {
        trash(demoProvider, "6", memberA);
        demoProvider.answerPurge("6", new PurgeOutcome.Blocked(TrashTestProviders.IN_USE));

        ResponseEntity<ProblemView> single = post(PURGE, tokenA, items(ref("demo", "6")), ProblemView.class);
        BulkView batch = post(PURGE, tokenA, items(ref("demo", "6"), ref("demo", "999")), BulkView.class).getBody();

        assertThat(single.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(single.getBody().detail()).isEqualTo("The item is in use");
        assertThat(batch.skipped()).containsExactly(
                new Skipped("demo", "6", "blocked", "The item is in use"),
                new Skipped("demo", "999", "notFound", null));
        assertThat(entryExists("demo", "6")).isTrue();
    }

    @Test
    void anOwningRowAlreadyGoneIsAPurgeThatSucceeds() {
        trash(demoProvider, "8", memberA);
        demoProvider.answerPurge("8", new PurgeOutcome.Gone());

        ResponseEntity<BulkView> answer = post(PURGE, tokenA, items(ref("demo", "8")), BulkView.class);

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(answer.getBody().succeeded()).containsExactly(new Ref("demo", "8"));
        assertThat(entryExists("demo", "8")).isFalse();
    }

    @Test
    void repeatedPurgeIsNotFoundTheSecondTime() {
        trash(demoProvider, "1", memberA);

        ResponseEntity<BulkView> first = post(PURGE, tokenA, items(ref("demo", "1")), BulkView.class);
        ResponseEntity<ProblemView> second = post(PURGE, tokenA, items(ref("demo", "1")), ProblemView.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(second.getBody().detail()).isEqualTo("This item is not in your Trash");
        assertThat(demoProvider.calls()).containsExactly("purge:1");
    }

    @Test
    void providerWriteRollsBackWithTheEntry() {
        trash(demoProvider, "7", memberA);
        demoProvider.failPurgeAfterWrite("7", new IllegalStateException("provider failed after its write"));

        BulkView answer = post(PURGE, tokenA, items(ref("demo", "7"), ref("demo", "999")), BulkView.class).getBody();

        assertThat(answer.skipped()).containsExactly(new Skipped("demo", "7", "failed", null),
                new Skipped("demo", "999", "notFound", null));
        assertThat(demoProvider.hasRow("7")).isTrue();
        assertThat(entryExists("demo", "7")).isTrue();
        assertThat(demoProvider.fileDeletes()).isEmpty();
    }

    @Test
    void afterCommitEffectDoesNotRunOnRollback() {
        trash(demoProvider, "9", memberA);
        refuseEntryDelete("9");

        BulkView refused = post(PURGE, tokenA, items(ref("demo", "9"), ref("demo", "999")), BulkView.class).getBody();

        assertThat(refused.skipped()).containsExactly(new Skipped("demo", "9", "failed", null),
                new Skipped("demo", "999", "notFound", null));
        assertThat(demoProvider.hasRow("9")).isTrue();
        assertThat(entryExists("demo", "9")).isTrue();
        assertThat(demoProvider.fileDeletes()).isEmpty();

        dropEntryDeleteRefusal();
        BulkView purged = post(PURGE, tokenA, items(ref("demo", "9")), BulkView.class).getBody();

        assertThat(purged.succeeded()).containsExactly(new Ref("demo", "9"));
        assertThat(demoProvider.hasRow("9")).isFalse();
        assertThat(demoProvider.fileDeletes()).containsExactly("9");
    }

    @Test
    void emptyStopsAt500AndReportsRemaining() {
        trashMany(demoProvider, "a", 620, memberA, 0);
        trashMany(demoProvider, "b", 10, memberB, 1_000);

        ResponseEntity<EmptyView> answer = post(EMPTY, tokenA, null, EmptyView.class);

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
        EmptyView body = answer.getBody();
        assertThat(body.purged()).isEqualTo(500);
        assertThat(body.blocked()).isZero();
        assertThat(body.failed()).isZero();
        assertThat(body.remaining()).isEqualTo(120);
        assertThat(body.next()).isNotNull();
        assertThat(entryCount(memberA)).isEqualTo(120);
        assertThat(entryCount(memberB)).isEqualTo(10);
    }

    @Test
    void emptyFollowedThroughNextExaminesEveryEntryOnce() {
        trashMany(demoProvider, "a", 620, memberA, 0);

        EmptyView first = post(EMPTY, tokenA, null, EmptyView.class).getBody();
        EmptyView second = post(EMPTY, tokenA, Map.of("next", first.next()), EmptyView.class).getBody();

        assertThat(second.purged()).isEqualTo(120);
        assertThat(second.remaining()).isZero();
        assertThat(second.next()).isNull();
        assertThat(entryCount(memberA)).isZero();
    }

    @Test
    void emptyReportsBlockedAndFailed() {
        trashMany(demoProvider, "e", 4, memberA, 0);
        demoProvider.answerPurge("e1", new PurgeOutcome.Blocked(TrashTestProviders.IN_USE));
        demoProvider.failPurge("e2", new IllegalStateException("provider is down"));

        EmptyView body = post(EMPTY, tokenA, null, EmptyView.class).getBody();

        assertThat(body).isEqualTo(new EmptyView(2, 1, 1, 2, null));
        assertThat(entryExists("demo", "e1")).isTrue();
        assertThat(entryExists("demo", "e2")).isTrue();
    }

    @Test
    void blockedAtTheFrontDoNotStallTheRun() {
        trashMany(demoProvider, "b", 3, memberA, 0);
        trashMany(demoProvider, "p", 400, memberA, 10);
        trashMany(demoLateProvider, "l", 200, memberA, 500);
        for (String id : List.of("b1", "b2", "b3")) {
            demoProvider.answerPurge(id, new PurgeOutcome.Blocked(TrashTestProviders.IN_USE));
        }

        EmptyView first = post(EMPTY, tokenA, null, EmptyView.class).getBody();
        EmptyView second = post(EMPTY, tokenA, Map.of("next", first.next()), EmptyView.class).getBody();

        assertThat(first.purged()).isEqualTo(497);
        assertThat(first.blocked()).isEqualTo(3);
        assertThat(first.next()).isNotNull();
        assertThat(second).isEqualTo(new EmptyView(103, 0, 0, 3, null));
        assertThat(callLog.entries()).filteredOn(call -> call.startsWith("demo:purge:b")).hasSize(3);
    }

    @Test
    void malformedCursorIs400AndCallsNoProvider() {
        trash(demoProvider, "1", memberA);

        ResponseEntity<ProblemView> answer = post(EMPTY, tokenA, Map.of("next", "!!"), ProblemView.class);

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(answer.getBody().errors()).containsKey("next");
        assertThat(callLog.entries()).isEmpty();
        assertThat(entryExists("demo", "1")).isTrue();
    }

    @Test
    void invalidBodiesAre400AndCallNoProvider() {
        trash(demoProvider, "1", memberA);
        List<Ref> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            tooMany.add(new Ref("demo", "t" + i));
        }
        List<Object> bodies = List.of(
                Map.of("items", List.of()),
                Map.of("items", tooMany),
                items(ref("demo", "a b")),
                items(ref("demo", "1"), ref("demo", "1")),
                items(ref("Demo", "1")),
                Map.of());

        for (String route : List.of(RESTORE, PURGE)) {
            for (Object body : bodies) {
                ResponseEntity<ProblemView> answer = post(route, tokenA, body, ProblemView.class);
                assertThat(answer.getStatusCode()).as("%s %s", route, body).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(answer.getBody().errors()).as("%s %s", route, body).containsKey("items");
            }
        }
        for (String route : List.of(RESTORE, PURGE)) {
            for (String json : unreadableBodies()) {
                ResponseEntity<ProblemView> answer = postJson(route, tokenA, json);
                assertThat(answer.getStatusCode()).as("%s %s", route, json).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(answer.getBody().errors()).as("%s %s", route, json).containsKey("items");
            }
        }
        ResponseEntity<ProblemView> notJson = postJson(EMPTY, tokenA, "{\"next\":");
        assertThat(notJson.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(notJson.getBody().errors()).containsKey("next");
        assertThat(callLog.entries()).isEmpty();
        assertThat(entryExists("demo", "1")).isTrue();
    }

    @Test
    void twoConcurrentPurgesOfOneItemPurgeOnce() throws Exception {
        trash(demoProvider, "1", memberA);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> purge = () -> {
                start.await();
                return post(PURGE, tokenA, items(ref("demo", "1")), String.class).getStatusCode().value();
            };
            Future<Integer> one = pool.submit(purge);
            Future<Integer> two = pool.submit(purge);
            start.countDown();

            List<Integer> statuses = List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));

            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK.value(), HttpStatus.NOT_FOUND.value());
            assertThat(demoProvider.calls()).containsExactly("purge:1");
            assertThat(demoProvider.fileDeletes()).containsExactly("1");
        } finally {
            pool.shutdownNow();
        }
    }

    /** A missing body, text that is not JSON, and an {@code items} that is not a list. */
    private static List<String> unreadableBodies() {
        return Arrays.asList(null, "{\"items\":[", "{\"items\":\"demo:1\"}");
    }

    private ResponseEntity<ProblemView> postJson(String path, String token, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), ProblemView.class);
    }

    private static Ref ref(String type, String id) {
        return new Ref(type, id);
    }

    private static Map<String, Object> items(Ref... refs) {
        return Map.of("items", List.of(refs));
    }

    private <T> ResponseEntity<T> postIn(String language, String path, String token, Object body, Class<T> type) {
        return restTemplate.exchange(path, HttpMethod.POST, entity(token, body, language), type);
    }

    private static HttpEntity<Object> entity(String token, Object body, String language) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (language != null) {
            headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
        }
        return new HttpEntity<>(body, headers);
    }

    private void trash(TrashTestProviders.ScriptedProvider provider, String id, UserView owner) {
        provider.trash(id, owner.id());
        record(provider.type(), id, owner);
    }

    private void record(String type, String id, UserView owner) {
        transaction.executeWithoutResult(status -> bin.record(
                new TrashRecord(type, id, owner.id(), owner.id(), "Item " + id, LOCATION)));
    }

    /**
     * {@code count} entries named {@code prefix1..prefixN}, deleted one second apart from {@code firstSecond} after
     * the seed instant, so a lower start sorts older. The clock is put back to the present afterwards.
     */
    private void trashMany(TrashTestProviders.ScriptedProvider provider, String prefix, int count, UserView owner,
            int firstSecond) {
        Instant base = Instant.now().minus(SEED_AGE);
        try {
            transaction.executeWithoutResult(status -> {
                for (int i = 1; i <= count; i++) {
                    String id = prefix + i;
                    provider.trash(id, owner.id());
                    clock.set(base.plusSeconds(firstSecond + i));
                    bin.record(new TrashRecord(provider.type(), id, owner.id(), owner.id(), "Item " + id, LOCATION));
                }
            });
        } finally {
            clock.set(Instant.now());
        }
    }

    private boolean entryExists(String type, String id) {
        return jdbc.queryForObject("SELECT count(*) FROM trash_entry WHERE item_type = ? AND item_id = ?",
                Long.class, type, id) > 0;
    }

    private long entryCount(UserView owner) {
        return jdbc.queryForObject("SELECT count(*) FROM trash_entry WHERE owner_id = ?", Long.class, owner.id());
    }

    private void refuseEntryDelete(String itemId) {
        jdbc.execute("CREATE OR REPLACE FUNCTION trash_actions_it_refuse() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'the entry delete is refused'; END $$");
        jdbc.execute("CREATE TRIGGER trash_actions_it_refuse BEFORE DELETE ON trash_entry FOR EACH ROW "
                + "WHEN (OLD.item_id = '" + itemId + "') EXECUTE FUNCTION trash_actions_it_refuse()");
    }

    private void dropEntryDeleteRefusal() {
        jdbc.execute("DROP TRIGGER IF EXISTS trash_actions_it_refuse ON trash_entry");
        jdbc.execute("DROP FUNCTION IF EXISTS trash_actions_it_refuse()");
    }

    private void reset() {
        jdbc.update("DELETE FROM trash_entry");
        demoProvider.reset();
        demoLateProvider.reset();
        callLog.clear();
    }
}
