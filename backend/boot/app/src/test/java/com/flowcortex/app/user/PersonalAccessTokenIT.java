package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.StepUpProofs;
import com.flowcortex.user.api.StepUpPurpose;
import com.flowcortex.user.application.AuthRetentionJob;
import com.flowcortex.user.application.OpaqueTokens;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Personal access tokens over real HTTP and the real database, switch on: the create, list and
 * revoke lifecycle, authentication through the shared decoder, the route allowlist, the live cap
 * under concurrency, last-use throttling and retention.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "flowcortex.auth.personal-access-tokens.enabled=true")
@AutoConfigureTestRestTemplate
class PersonalAccessTokenIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "pat-password-123";

    @Autowired
    private StepUpProofs stepUpProofs;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuthRetentionJob retentionJob;

    record Account(UUID id, String email, String session) {
    }

    record ProofView(String stepUpProof) {
    }

    record CreatedView(UUID id, String name, String token, Instant createdAt, Instant expiresAt) {
    }

    record TokenView(UUID id, String name, Instant createdAt, Instant expiresAt, Instant lastUsedAt, boolean expired) {
    }

    record ProblemView(int status, String type, String code) {
    }

    /** The JDK client returns a 401 as is; the default one retries it. */
    private TestRestTemplate client() {
        return new TestRestTemplate(new RestTemplateBuilder()
                .requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk()).rootUri(restTemplate.getRootUri()));
    }

    private Account admin(String prefix) {
        String email = uniqueEmail(prefix);
        UUID id = userService.createInitialAdmin(email, PASSWORD, "Pat Admin").id();
        return new Account(id, email, login(email, PASSWORD));
    }

    private String proof(Account account) {
        return exchange("/api/v1/me/step-up", HttpMethod.POST, account.session(),
                Map.of("password", PASSWORD, "purpose", "PAT_CREATE"), ProofView.class).getBody().stepUpProof();
    }

    private <T> ResponseEntity<T> exchange(String path, HttpMethod method, String bearer, Object body,
            Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.ALL));
        return client().exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    private ResponseEntity<CreatedView> create(Account account, String name, Integer days) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (days != null) {
            body.put("expiresInDays", days);
        }
        body.put("stepUpProof", proof(account));
        return exchange("/api/v1/me/api-tokens", HttpMethod.POST, account.session(), body, CreatedView.class);
    }

    private String createToken(Account account, String name) {
        return create(account, name, null).getBody().token();
    }

    private HttpStatus status(String path, HttpMethod method, String bearer) {
        return HttpStatus.valueOf(exchange(path, method, bearer, null, String.class).getStatusCode().value());
    }

    private void insertToken(UUID userId, String raw, String name, String expiresAt, String revokedAt) {
        jdbc.update("INSERT INTO personal_access_token (id, user_id, name, token_hash, created_at, expires_at,"
                + " revoked_at) VALUES (?, ?, ?, ?, now() - interval '1 hour', " + expiresAt + ", " + revokedAt + ")",
                UUID.randomUUID(), userId, name, OpaqueTokens.hash(raw));
    }

    @Test
    void anAdminStepsUpCreatesSeesTheTokenOnceAndTheListHoldsNeitherTokenNorHash() {
        Account a = admin("create");

        ResponseEntity<CreatedView> created = create(a, "CI runner", null);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = created.getBody().token();
        assertThat(token).matches("^fc_pat_[A-Za-z0-9_-]{43}$");
        assertThat(created.getBody().expiresAt()).isAfter(Instant.now().plus(java.time.Duration.ofDays(89)))
                .isBefore(Instant.now().plus(java.time.Duration.ofDays(91)));
        assertThat(jdbc.queryForList("SELECT token_hash FROM personal_access_token WHERE user_id = ?", String.class,
                a.id())).containsExactly(OpaqueTokens.hash(token));
        createToken(a, "second");

        ResponseEntity<String> list = exchange("/api/v1/me/api-tokens", HttpMethod.GET, a.session(), null,
                String.class);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).doesNotContain("fc_pat_").doesNotContainPattern("[0-9a-f]{64}");
        ResponseEntity<TokenView[]> views = exchange("/api/v1/me/api-tokens", HttpMethod.GET, a.session(), null,
                TokenView[].class);
        assertThat(views.getBody()).extracting(TokenView::name).containsExactly("second", "CI runner");
        assertThat(views.getBody()).allSatisfy(view -> {
            assertThat(view.lastUsedAt()).isNull();
            assertThat(view.expired()).isFalse();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_event WHERE user_id = ? AND type = 'PAT_CREATED'",
                Integer.class, a.id())).isEqualTo(2);
        assertThat(String.join(" ", jdbc.queryForList("SELECT metadata::text FROM auth_event WHERE user_id = ?",
                String.class, a.id()))).doesNotContain("fc_pat_");
    }

    @Test
    void creatingWithoutAProofOrWithAnInvalidLifetimeIsRefusedAndALongLifetimeKeepsTheProof() {
        Account a = admin("refusals");
        Map<String, Object> noProof = Map.of("name", "CI");
        String proof = proof(a);
        Map<String, Object> tooLong = Map.of("name", "CI", "expiresInDays", 400, "stepUpProof", proof);
        Map<String, Object> explicitNull = new LinkedHashMap<>();
        explicitNull.put("name", "CI");
        explicitNull.put("expiresInDays", null);
        explicitNull.put("stepUpProof", proof);

        ResponseEntity<ProblemView> missing = exchange("/api/v1/me/api-tokens", HttpMethod.POST, a.session(),
                noProof, ProblemView.class);
        ResponseEntity<ProblemView> long400 = exchange("/api/v1/me/api-tokens", HttpMethod.POST, a.session(), tooLong,
                ProblemView.class);
        ResponseEntity<ProblemView> nullDays = exchange("/api/v1/me/api-tokens", HttpMethod.POST, a.session(),
                explicitNull, ProblemView.class);
        ResponseEntity<ProblemView> wrongProof = exchange("/api/v1/me/api-tokens", HttpMethod.POST, a.session(),
                Map.of("name", "CI", "stepUpProof", "not-a-proof"), ProblemView.class);

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(missing.getBody().code()).isEqualTo("STEP_UP_REQUIRED");
        assertThat(long400.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(long400.getBody().type()).endsWith("/validation-failed");
        assertThat(nullDays.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(nullDays.getBody().type()).endsWith("/validation-failed");
        assertThat(wrongProof.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(wrongProof.getBody().code()).isEqualTo("STEP_UP_REQUIRED");
        assertThat(count(a.id())).isZero();
        stepUpProofs.consume(a.id(), StepUpPurpose.PAT_CREATE, proof);
    }

    @Test
    void aTokenAuthenticatesOnAllowedRoutesAndIsRefusedWithoutRunningAnyHandlerOnTheRest() {
        Account a = admin("routes");
        String token = createToken(a, "routes");

        assertThat(status("/api/v1/workflows", HttpMethod.GET, token)).isEqualTo(HttpStatus.OK);
        ResponseEntity<MeView> me = exchange("/api/v1/auth/me", HttpMethod.GET, token, null, MeView.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().id()).isEqualTo(a.id());
        assertThat(me.getBody().email()).isEqualTo(a.email());

        String spent = proof(a);
        for (Object[] route : new Object[][] {{"/api/v1/admin/runs", HttpMethod.GET}, {"/api/v1/users", HttpMethod.GET},
                {"/api/v1/me/step-up", HttpMethod.POST}, {"/api/v1/me/password", HttpMethod.PUT},
                {"/api/v1/me/phone", HttpMethod.GET}, {"/api/v1/realtime/stream", HttpMethod.GET},
                {"/api/v1/auth/profile", HttpMethod.PUT}}) {
            ResponseEntity<ProblemView> response = exchange((String) route[0], (HttpMethod) route[1], token,
                    Map.of("password", PASSWORD, "purpose", "PAT_CREATE"), ProblemView.class);
            assertThat(response.getStatusCode()).as("%s %s", route[1], route[0]).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody().code()).as("%s %s", route[1], route[0]).isEqualTo("PAT_NOT_ALLOWED");
        }
        ResponseEntity<ProblemView> mint = exchange("/api/v1/me/api-tokens", HttpMethod.POST, token,
                Map.of("name", "child", "stepUpProof", spent), ProblemView.class);
        assertThat(mint.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(mint.getBody().code()).isEqualTo("PAT_NOT_ALLOWED");
        assertThat(count(a.id())).isEqualTo(1);
        stepUpProofs.consume(a.id(), StepUpPurpose.PAT_CREATE, spent);
    }

    @Test
    void aNonAdminRouteDenialForASessionStillRendersTheGenericForbidden() {
        String email = uniqueEmail("plain-forbidden");
        UUID ownerId = userService.createInitialAdmin(uniqueEmail("plain-owner"), PASSWORD, "Owner").id();
        userService.createUser(ownerId, email, PASSWORD, "Member", "MEMBER");

        ResponseEntity<ProblemView> response = exchange("/api/v1/admin/runs", HttpMethod.GET,
                login(email, PASSWORD), null, ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().type()).endsWith("/forbidden");
    }

    @Test
    void revokingRefusesTheTokenAtOnceAndASecondOrForeignRevokeIsNotFound() {
        Account a = admin("revoke");
        Account b = admin("revoke-other");
        CreatedView created = create(a, "to revoke", null).getBody();

        assertThat(status("/api/v1/me/api-tokens/" + created.id(), HttpMethod.DELETE, b.session()))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status("/api/v1/workflows", HttpMethod.GET, created.token())).isEqualTo(HttpStatus.OK);
        assertThat(status("/api/v1/me/api-tokens/" + created.id(), HttpMethod.DELETE, a.session()))
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(status("/api/v1/workflows", HttpMethod.GET, created.token())).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status("/api/v1/me/api-tokens/" + created.id(), HttpMethod.DELETE, a.session()))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_event WHERE user_id = ? AND type = 'PAT_REVOKED'",
                Integer.class, a.id())).isEqualTo(1);
    }

    @Test
    void aDemotedOrSuspendedOwnerLosesTheTokenAndALockedOwnerKeepsIt() {
        Account demoted = admin("demoted");
        Account suspended = admin("suspended");
        Account locked = admin("locked");
        String demotedToken = createToken(demoted, "d");
        String suspendedToken = createToken(suspended, "s");
        String lockedToken = createToken(locked, "l");

        jdbc.update("UPDATE app_user SET role = 'MEMBER' WHERE id = ?", demoted.id());
        jdbc.update("UPDATE app_user SET status = 'SUSPENDED' WHERE id = ?", suspended.id());
        for (int attempt = 0; attempt < 5; attempt++) {
            loginRaw(locked.email(), "wrong-password-" + attempt);
        }

        assertThat(status("/api/v1/workflows", HttpMethod.GET, demotedToken)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status("/api/v1/workflows", HttpMethod.GET, suspendedToken)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject("SELECT locked_until > now() FROM app_user WHERE id = ?", Boolean.class,
                locked.id())).isTrue();
        assertThat(status("/api/v1/workflows", HttpMethod.GET, lockedToken)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anExpiredOrUnknownTokenIs401() {
        Account a = admin("dead");
        String expired = "fc_pat_" + OpaqueTokens.generateRawToken();
        insertToken(a.id(), expired, "old", "now() - interval '1 second'", "NULL");

        assertThat(status("/api/v1/workflows", HttpMethod.GET, expired)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status("/api/v1/workflows", HttpMethod.GET, "fc_pat_" + OpaqueTokens.generateRawToken()))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aMemberListingOrCreatingIsNotEligible() {
        UUID ownerId = userService.createInitialAdmin(uniqueEmail("member-owner"), PASSWORD, "Owner").id();
        String email = uniqueEmail("member");
        userService.createUser(ownerId, email, PASSWORD, "Member", "MEMBER");
        String session = login(email, PASSWORD);

        ResponseEntity<ProblemView> list = exchange("/api/v1/me/api-tokens", HttpMethod.GET, session, null,
                ProblemView.class);
        ResponseEntity<ProblemView> create = exchange("/api/v1/me/api-tokens", HttpMethod.POST, session,
                Map.of("name", "CI", "stepUpProof", "x"), ProblemView.class);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(list.getBody().code()).isEqualTo("PAT_NOT_ELIGIBLE");
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(create.getBody().code()).isEqualTo("PAT_NOT_ELIGIBLE");
    }

    @Test
    void theEleventhTokenAnswers409AndLeavesTheProofUsable() {
        Account a = admin("cap");
        for (int i = 0; i < 10; i++) {
            insertToken(a.id(), "fc_pat_cap-" + i + "-" + UUID.randomUUID(), "t" + i, "now() + interval '30 days'",
                    "NULL");
        }
        String proof = proof(a);

        ResponseEntity<ProblemView> response = exchange("/api/v1/me/api-tokens", HttpMethod.POST, a.session(),
                Map.of("name", "eleventh", "stepUpProof", proof), ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("PAT_LIMIT_REACHED");
        assertThat(count(a.id())).isEqualTo(10);
        stepUpProofs.consume(a.id(), StepUpPurpose.PAT_CREATE, proof);
    }

    @Test
    void twoConcurrentCreatesAtNineLiveTokensYieldExactlyOneCreated() throws Exception {
        Account a = admin("race");
        for (int i = 0; i < 9; i++) {
            insertToken(a.id(), "fc_pat_race-" + i + "-" + UUID.randomUUID(), "t" + i, "now() + interval '30 days'",
                    "NULL");
        }
        List<String> proofs = List.of(proof(a), proof(a));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatus>> results = new ArrayList<>();
        try {
            for (String proof : proofs) {
                results.add(pool.submit(() -> {
                    start.await();
                    return HttpStatus.valueOf(exchange("/api/v1/me/api-tokens", HttpMethod.POST, a.session(),
                            Map.of("name", "racer", "stepUpProof", proof), String.class).getStatusCode().value());
                }));
            }
            start.countDown();
            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<HttpStatus> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count(a.id())).isEqualTo(10);
    }

    @Test
    void lastUseIsWrittenOnceOverABurstOfCalls() {
        Account a = admin("last-used");
        String token = createToken(a, "burst");

        assertThat(status("/api/v1/workflows", HttpMethod.GET, token)).isEqualTo(HttpStatus.OK);
        Timestamp first = jdbc.queryForObject("SELECT last_used_at FROM personal_access_token WHERE user_id = ?",
                Timestamp.class, a.id());
        for (int i = 0; i < 5; i++) {
            assertThat(status("/api/v1/workflows", HttpMethod.GET, token)).isEqualTo(HttpStatus.OK);
        }
        Timestamp afterBurst = jdbc.queryForObject("SELECT last_used_at FROM personal_access_token WHERE user_id = ?",
                Timestamp.class, a.id());

        assertThat(first).isNotNull();
        assertThat(afterBurst).isEqualTo(first);
    }

    @Test
    void aReplayedUnknownTokenIsThrottledAfterSixtyRequests() {
        String unknown = "fc_pat_" + OpaqueTokens.generateRawToken();
        TestRestTemplate client = client();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(unknown);
        List<HttpStatus> statuses = new ArrayList<>();
        for (int i = 0; i < 61; i++) {
            statuses.add(HttpStatus.valueOf(client.exchange("/api/v1/workflows", HttpMethod.GET,
                    new HttpEntity<>(headers), String.class).getStatusCode().value()));
        }

        assertThat(statuses.subList(0, 60)).containsOnly(HttpStatus.UNAUTHORIZED);
        assertThat(statuses.get(60)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void theFeaturesEndpointReportsTheSwitchOn() {
        assertThat(restTemplate.getForObject("/api/v1/features", Map.class)).containsEntry("personalAccessTokens", true);
    }

    @Test
    void retentionKeepsOnlyLiveTokensAndRecentlyExpiredOnes() {
        Account a = admin("retention");
        insertToken(a.id(), "fc_pat_r-" + UUID.randomUUID(), "revoked", "now() + interval '30 days'",
                "now() - interval '1 minute'");
        insertToken(a.id(), "fc_pat_e-" + UUID.randomUUID(), "long expired", "now() - interval '100 days'", "NULL");
        insertToken(a.id(), "fc_pat_y-" + UUID.randomUUID(), "expired yesterday", "now() - interval '1 day'", "NULL");
        insertToken(a.id(), "fc_pat_l-" + UUID.randomUUID(), "live", "now() + interval '30 days'", "NULL");

        retentionJob.purge();

        assertThat(jdbc.queryForList("SELECT name FROM personal_access_token WHERE user_id = ?", String.class,
                a.id())).containsExactlyInAnyOrder("expired yesterday", "live");
    }

    private int count(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM personal_access_token WHERE user_id = ?", Integer.class,
                userId);
    }
}
