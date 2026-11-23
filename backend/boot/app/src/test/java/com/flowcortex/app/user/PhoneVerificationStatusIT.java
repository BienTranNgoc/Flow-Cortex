package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * {@code GET /api/v1/admin/phone-verification/status} against the real database: the flags the
 * process runs with, the daily budget, and the codes sent in the last 24 hours. Rows the cases
 * insert carry their own account's id and are deleted again.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = "flowcortex.phone-verification.enabled=true")
class PhoneVerificationStatusIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "password123";
    private static final String STATUS = "/api/v1/admin/phone-verification/status";
    private static final String HASH_SECRET = "phone-hash-secret-for-integration-tests-0123456789";
    private static final String ROW_HASH = "c".repeat(64);

    @Autowired
    private JdbcTemplate jdbc;

    record StatusView(
            Boolean verificationEnabled, String smsSender, Boolean hashSecretConfigured, Boolean gateEnabled,
            Integer globalSendsPerDay, Long sentLast24h) {
    }

    @Test
    void aSuperAdminReadsTheStatusWithoutSecrets() {
        UserView admin = userService.createInitialAdmin(uniqueEmail("status-admin"), PASSWORD, "Status Admin");
        String token = login(admin.email(), PASSWORD);
        long before = get(STATUS, token, StatusView.class).getBody().sentLast24h();
        Instant now = Instant.now();
        try {
            for (int i = 0; i < 12; i++) {
                attempt(admin, "sent", now.minus(Duration.ofMinutes(i + 1)));
            }
            attempt(admin, "sent", now.minus(Duration.ofHours(25)));
            attempt(admin, "sent", now.minus(Duration.ofHours(48)));
            attempt(admin, "number_taken", now);
            attempt(admin, "cooldown", now);

            ResponseEntity<String> raw = get(STATUS, token, String.class);
            ResponseEntity<StatusView> typed = get(STATUS, token, StatusView.class);

            assertThat(typed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(typed.getBody()).isEqualTo(new StatusView(true, "outbox", true, false, 2000, before + 12));
            assertThat(raw.getBody()).doesNotContain(HASH_SECRET, ROW_HASH, "+84");
        } finally {
            jdbc.update("DELETE FROM phone_send_attempt WHERE user_id = ?", admin.id());
        }
    }

    @Test
    void aMemberIs403() {
        UserView admin = userService.createInitialAdmin(uniqueEmail("status-owner"), PASSWORD, "Status Owner");
        UserView member = userService.createUser(admin.id(), uniqueEmail("status-member"), PASSWORD, "Member", "MEMBER");

        ResponseEntity<String> response = get(STATUS, login(member.email(), PASSWORD), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anAnonymousCallIs401() {
        assertThat(get(STATUS, null, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Nested
    @TestPropertySource(properties = {"flowcortex.phone-verification.enabled=false", "flowcortex.sms.sender="})
    class WithNoSenderWired {

        @Autowired
        private TestRestTemplate noSenderRestTemplate;

        @Autowired
        private UserService noSenderUserService;

        @Test
        void theSenderReadsNoneAndTheSwitchesReadOff() {
            UserView admin = noSenderUserService.createInitialAdmin(uniqueEmail("status-none"), PASSWORD, "Status None");
            String token = noSenderRestTemplate.postForEntity("/api/v1/auth/login",
                    new LoginRequest(admin.email(), PASSWORD), TokenResponse.class).getBody().accessToken();
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);

            ResponseEntity<StatusView> response = noSenderRestTemplate.exchange(STATUS, HttpMethod.GET,
                    new HttpEntity<>(headers), StatusView.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().smsSender()).isEqualTo("none");
            assertThat(response.getBody().verificationEnabled()).isFalse();
            assertThat(response.getBody().gateEnabled()).isFalse();
        }
    }

    private void attempt(UserView user, String outcome, Instant at) {
        jdbc.update("INSERT INTO phone_send_attempt (id, user_id, phone_hash, ip, outcome, created_at) "
                + "VALUES (?, ?, ?, '198.51.100.9', ?, ?)", UUID.randomUUID(), user.id(), ROW_HASH, outcome,
                Timestamp.from(at));
    }
}
