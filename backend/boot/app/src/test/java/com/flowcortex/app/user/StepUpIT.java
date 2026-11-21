package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.user.api.StepUpProofs;
import com.flowcortex.user.api.StepUpPurpose;
import com.flowcortex.user.api.StepUpRequiredException;
import com.flowcortex.user.application.AuthRetentionJob;
import com.flowcortex.user.application.OpaqueTokens;
import java.sql.Timestamp;
import java.time.Duration;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Step-up over real HTTP and the real database: minting, the closed purpose list, the distinct
 * refusals, the attempt limit, and the single-use rules that only the database decides.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "flowcortex.registration.owner-email=" + StepUpIT.OWNER_EMAIL)
@AutoConfigureTestRestTemplate
@Import(StepUpIT.MovableClockConfig.class)
class StepUpIT extends AbstractUserModuleIT {

    static final String OWNER_EMAIL = "step-up-owner@example.com";
    private static final String OWNER_PASSWORD = "owner-password-1";
    private static final String PASSWORD = "step-up-password-1";

    @Autowired
    private StepUpProofs stepUpProofs;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MovableClock clock;

    @Autowired
    private AuthRetentionJob retentionJob;

    /** The JDK client returns a 429 as is; the default one would sleep through Retry-After. */
    private TestRestTemplate client;

    private UUID ownerId;

    record StepUpRequest(String password, String purpose) {
    }

    record StepUpView(String stepUpProof, Instant expiresAt) {
    }

    record ProblemView(int status, String type, String detail, String code, Long retryAfterSeconds,
            Map<String, String> errors) {
    }

    @BeforeEach
    void ownerAndClient() {
        client = new TestRestTemplate(new RestTemplateBuilder()
                .requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk())
                .rootUri(restTemplate.getRootUri()));
        if (jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = ?", Integer.class, OWNER_EMAIL) == 0) {
            userService.createInitialAdmin(OWNER_EMAIL, OWNER_PASSWORD, "Step Up Owner");
        }
        ownerId = jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", UUID.class, OWNER_EMAIL);
        clock.reset();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void correctPasswordMintsAProofStoredOnlyAsItsHashThatIsSpentOnce() {
        Account u = account("mint");

        ResponseEntity<StepUpView> response = stepUp(u.token(), PASSWORD, "PAT_CREATE", StepUpView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String proof = response.getBody().stepUpProof();
        assertThat(proof).isNotBlank();
        assertThat(response.getBody().expiresAt()).isAfter(Instant.now().plusSeconds(290))
                .isBefore(Instant.now().plusSeconds(310));
        assertThat(jdbc.queryForList("SELECT token_hash FROM step_up_proof WHERE user_id = ?", String.class, u.id()))
                .containsExactly(OpaqueTokens.hash(proof));
        assertThat(auditCount(u.id(), "STEP_UP_SUCCEEDED")).isEqualTo(1);
        assertThat(auditMetadata(u.id())).doesNotContain(proof).doesNotContain(PASSWORD);

        stepUpProofs.consume(u.id(), StepUpPurpose.PAT_CREATE, proof);

        assertThatThrownBy(() -> stepUpProofs.consume(u.id(), StepUpPurpose.PAT_CREATE, proof))
                .isInstanceOf(StepUpRequiredException.class);
    }

    @Test
    void anUnknownOrMissingPurposeIsRefusedAndMintsNothing() {
        Account u = account("purpose");

        ResponseEntity<ProblemView> unknown = stepUp(u.token(), PASSWORD, "DELETE_ACCOUNT", ProblemView.class);
        ResponseEntity<ProblemView> missing = stepUp(u.token(), PASSWORD, null, ProblemView.class);

        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getBody().type()).endsWith("/validation-failed");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody().type()).endsWith("/validation-failed");
        assertThat(proofRows(u.id())).isZero();
    }

    @Test
    void aWrongPasswordAnswers422NotAnUnauthorizedAndTouchesNoLockoutState() {
        Account u = account("wrong");

        ResponseEntity<ProblemView> response = stepUp(u.token(), "wrong-password", "PAT_CREATE", ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().code()).isEqualTo("STEP_UP_INVALID_PASSWORD");
        assertThat(proofRows(u.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT failed_login_attempts FROM app_user WHERE id = ?", Integer.class,
                u.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT locked_until FROM app_user WHERE id = ?", Timestamp.class, u.id()))
                .isNull();
        assertThat(auditCount(u.id(), "STEP_UP_FAILED_INVALID_PASSWORD")).isEqualTo(1);
        assertThat(auditMetadata(u.id())).doesNotContain("wrong-password");
    }

    @Test
    void anAccountWithoutAPasswordAnswers409() {
        Account g = account("google");
        jdbc.update("UPDATE app_user SET has_password = false, password_hash = '' WHERE id = ?", g.id());

        ResponseEntity<ProblemView> response = stepUp(g.token(), "anything", "PAT_CREATE", ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("STEP_UP_PASSWORD_REQUIRED");
        assertThat(proofRows(g.id())).isZero();
        assertThat(auditCount(g.id(), "STEP_UP_FAILED_PASSWORD_REQUIRED")).isEqualTo(1);
    }

    @Test
    void theSixthAttemptInAMinuteIsRateLimitedAndAnotherUserIsNotAffected() {
        Account u = account("limited");
        Account v = account("unaffected");
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(stepUp(u.token(), "wrong-password", "PAT_CREATE", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }

        ResponseEntity<ProblemView> sixth = stepUp(u.token(), PASSWORD, "PAT_CREATE", ProblemView.class);
        ResponseEntity<StepUpView> other = stepUp(v.token(), PASSWORD, "PAT_CREATE", StepUpView.class);

        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(sixth.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isPositive();
        assertThat(sixth.getBody().code()).isEqualTo("STEP_UP_RATE_LIMITED");
        assertThat(proofRows(u.id())).isZero();
        assertThat(auditCount(u.id(), "STEP_UP_RATE_LIMITED")).isEqualTo(1);
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aProofIsRefusedFiveMinutesAndOneSecondAfterItWasMinted() {
        Account u = account("expired");
        String proof = stepUp(u.token(), PASSWORD, "PAT_CREATE", StepUpView.class).getBody().stepUpProof();

        clock.advance(Duration.ofSeconds(301));

        assertThatThrownBy(() -> stepUpProofs.consume(u.id(), StepUpPurpose.PAT_CREATE, proof))
                .isInstanceOf(StepUpRequiredException.class);
    }

    @Test
    void anotherUsersAttemptSpendsTheProofSoItsOwnerCanNoLongerUseIt() {
        Account u = account("owner-of-proof");
        Account v = account("thief");
        String proof = stepUp(u.token(), PASSWORD, "PAT_CREATE", StepUpView.class).getBody().stepUpProof();

        assertThatThrownBy(() -> stepUpProofs.consume(v.id(), StepUpPurpose.PAT_CREATE, proof))
                .isInstanceOf(StepUpRequiredException.class);
        assertThatThrownBy(() -> stepUpProofs.consume(u.id(), StepUpPurpose.PAT_CREATE, proof))
                .isInstanceOf(StepUpRequiredException.class);
    }

    @Test
    void aProofMintedForAnotherPurposeIsSpentByTheWrongPurposeAttempt() {
        Account u = account("purposes");
        String proof = OpaqueTokens.generateRawToken();
        jdbc.update("INSERT INTO step_up_proof (id, user_id, purpose, token_hash, created_at, expires_at)"
                + " VALUES (?, ?, 'OTHER', ?, ?, ?)", UUID.randomUUID(), u.id(), OpaqueTokens.hash(proof),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now().plusSeconds(300)));

        assertThatThrownBy(() -> stepUpProofs.consume(u.id(), StepUpPurpose.PAT_CREATE, proof))
                .isInstanceOf(StepUpRequiredException.class);
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM step_up_proof WHERE token_hash = ?",
                Boolean.class, OpaqueTokens.hash(proof))).isTrue();
        assertThatThrownBy(() -> stepUpProofs.consume(u.id(), StepUpPurpose.PAT_CREATE, proof))
                .isInstanceOf(StepUpRequiredException.class);
    }

    @Test
    void theRetentionPurgeDeletesSpentAndExpiredProofsAndKeepsALiveOne() {
        Account u = account("purge");
        Instant now = Instant.now();
        insertProof(u.id(), "spent", now.minusSeconds(60), now.plusSeconds(300), now.minusSeconds(30));
        insertProof(u.id(), "expired", now.minusSeconds(900), now.minusSeconds(600), null);
        insertProof(u.id(), "live", now.minusSeconds(10), now.plusSeconds(300), null);

        retentionJob.purge();

        assertThat(jdbc.queryForList("SELECT token_hash FROM step_up_proof WHERE user_id = ?", String.class, u.id()))
                .containsExactly(OpaqueTokens.hash("live"));
    }

    @Test
    void aRefusedConsumeStillCommitsTheSpendWhenTheCallersTransactionRollsBack() {
        Account u = account("rollback");
        String proof = stepUp(u.token(), PASSWORD, "PAT_CREATE", StepUpView.class).getBody().stepUpProof();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThatThrownBy(() -> stepUpProofs.consume(UUID.randomUUID(), StepUpPurpose.PAT_CREATE, proof))
                    .isInstanceOf(StepUpRequiredException.class);
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM step_up_proof WHERE token_hash = ?",
                Boolean.class, OpaqueTokens.hash(proof))).isTrue();
    }

    @Test
    void twoThreadsConsumingOneProofSucceedExactlyOnce() throws Exception {
        Account u = account("race");
        String proof = stepUp(u.token(), PASSWORD, "PAT_CREATE", StepUpView.class).getBody().stepUpProof();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        try {
            List<Future<?>> running = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                running.add(pool.submit(() -> {
                    start.await();
                    try {
                        stepUpProofs.consume(u.id(), StepUpPurpose.PAT_CREATE, proof);
                        accepted.incrementAndGet();
                    } catch (StepUpRequiredException ex) {
                        refused.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : running) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(accepted.get()).isEqualTo(1);
        assertThat(refused.get()).isEqualTo(threads - 1);
    }

    private void insertProof(UUID userId, String raw, Instant createdAt, Instant expiresAt, Instant consumedAt) {
        jdbc.update("INSERT INTO step_up_proof (id, user_id, purpose, token_hash, created_at, expires_at, consumed_at)"
                + " VALUES (?, ?, 'PAT_CREATE', ?, ?, ?, ?)", UUID.randomUUID(), userId, OpaqueTokens.hash(raw),
                Timestamp.from(createdAt), Timestamp.from(expiresAt),
                consumedAt == null ? null : Timestamp.from(consumedAt));
    }

    private record Account(UUID id, String token) {
    }

    private Account account(String prefix) {
        String email = uniqueEmail(prefix);
        userService.createUser(ownerId, email, PASSWORD, "Step Up", "MEMBER");
        UUID id = jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", UUID.class, email);
        return new Account(id, login(email, PASSWORD));
    }

    private <T> ResponseEntity<T> stepUp(String token, String password, String purpose, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("password", password);
        if (purpose != null) {
            body.put("purpose", purpose);
        }
        return client.exchange("/api/v1/me/step-up", HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    private int proofRows(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM step_up_proof WHERE user_id = ?", Integer.class, userId);
    }

    private int auditCount(UUID userId, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM auth_event WHERE user_id = ? AND type = ?", Integer.class,
                userId, type);
    }

    private String auditMetadata(UUID userId) {
        return String.join(" ", jdbc.queryForList(
                "SELECT metadata::text FROM auth_event WHERE user_id = ? AND type LIKE 'STEP_UP_%'", String.class,
                userId));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MovableClockConfig {

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }
}
