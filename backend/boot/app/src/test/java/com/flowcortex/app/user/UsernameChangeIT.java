package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Username changes under the shared policy, cooldown and hold, plus the same policy at registration
 * and first password setup, against the real app. Time travel is done on the stored stamps, not the
 * clock, so access tokens stay valid.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "flowcortex.registration.owner-email=" + UsernameChangeIT.OWNER_EMAIL)
@TestPropertySource(properties = "flowcortex.auth.email-confirmation.enabled=true")
@AutoConfigureTestRestTemplate
@Import(UsernameChangeIT.Mail.class)
class UsernameChangeIT extends AbstractUserModuleIT {

    static final String OWNER_EMAIL = "username-owner@example.com";
    private static final String OWNER_PASSWORD = "owner-password-1";
    private static final String PASSWORD = "correct-password-1";
    private static final String PATH = "/api/v1/auth/profile/username";
    private static final Pattern CONFIRM_LINK = Pattern.compile(
            "http://localhost:5173/confirm-email\\?token=([A-Za-z0-9_-]+)");
    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger(1);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CapturingMailSender mail;

    private TestRestTemplate client;

    private record Member(UUID id, String email, String token) {
    }

    record UsernameRequest(String username) {
    }

    record RegisterRequest(String firstName, String lastName, String username, String email, String password) {
    }

    record ConfirmRequest(String token) {
    }

    record ConfirmedView(String accessToken, boolean passwordSetupRequired, String passwordSetupToken) {
    }

    record PasswordRequest(String passwordSetupToken, String password, String firstName, String lastName,
            String username) {
    }

    record ProfileView(String name, String username, Instant usernameNextChangeAt) {
    }

    record ProblemView(int status, String detail, String code, Map<String, String> errors,
            Instant nextChangeAt) {
    }

    @BeforeEach
    void ownerAndClient() {
        client = new TestRestTemplate(new RestTemplateBuilder()
                .requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk())
                .rootUri(restTemplate.getRootUri()));
        if (jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = ?", Integer.class, OWNER_EMAIL) == 0) {
            userService.createInitialAdmin(OWNER_EMAIL, OWNER_PASSWORD, "Username Owner");
        }
    }

    private static String freshName() {
        return "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private Member newMember(String storedUsername) {
        UserView admin = userService.createInitialAdmin(uniqueEmail("username-admin"), PASSWORD, "Username Admin");
        String email = uniqueEmail("username-member");
        UserView member = userService.createUser(admin.id(), email, PASSWORD, "Username Member", "MEMBER");
        if (storedUsername != null) {
            jdbc.update("UPDATE app_user SET username = ? WHERE id = ?", storedUsername, member.id());
        }
        return new Member(member.id(), email, login(email, PASSWORD));
    }

    private ResponseEntity<ProblemView> change(Member member, String username) {
        return put(PATH, member.token(), new UsernameRequest(username), ProblemView.class);
    }

    private ResponseEntity<ProfileView> changeOk(Member member, String username) {
        ResponseEntity<ProfileView> response = put(PATH, member.token(), new UsernameRequest(username),
                ProfileView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private void endCooldown(Member member) {
        jdbc.update("UPDATE app_user SET username_changed_at = now() - interval '31 days' WHERE id = ?",
                member.id());
    }

    private String ownerOf(String username) {
        List<UUID> owners = jdbc.queryForList("SELECT id FROM app_user WHERE username = ?", UUID.class, username);
        return owners.stream().map(UUID::toString).findFirst().orElse(null);
    }

    private <T> ResponseEntity<T> postFromNewClient(String path, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", "203.0.113." + NEXT_CLIENT.getAndIncrement());
        return client.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    @Test
    void heldNameIsTakenForAnotherMember() {
        String old = freshName();
        Member former = newMember(old);
        Member other = newMember(null);
        changeOk(former, freshName());

        ResponseEntity<ProblemView> response = change(other, old);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ownerOf(old)).isNull();
    }

    @Test
    void formerOwnerTakesHeldNameBackAfterTheLock() {
        String old = freshName();
        Member former = newMember(old);
        changeOk(former, freshName());
        endCooldown(former);

        ProfileView back = changeOk(former, old).getBody();

        assertThat(back.username()).isEqualTo(old);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM username_hold WHERE username = ?", Integer.class, old))
                .as("taking a name drops its hold").isZero();
    }

    @Test
    void expiredHoldFreesTheName() {
        String old = freshName();
        Member former = newMember(old);
        Member other = newMember(null);
        changeOk(former, freshName());
        jdbc.update("UPDATE username_hold SET expires_at = now() - interval '1 day' WHERE username = ?", old);

        ProfileView taken = changeOk(other, old).getBody();

        assertThat(taken.username()).isEqualTo(old);
        assertThat(ownerOf(old)).isEqualTo(other.id().toString());
    }

    @Test
    void twoConcurrentClaimsOfOneFreeNameEndWithOneOwnerAnd409() {
        Member first = newMember(null);
        Member second = newMember(null);
        String name = freshName();
        CountDownLatch go = new CountDownLatch(1);

        CompletableFuture<HttpStatus> a = CompletableFuture.supplyAsync(() -> claimOnSignal(go, first, name));
        CompletableFuture<HttpStatus> b = CompletableFuture.supplyAsync(() -> claimOnSignal(go, second, name));
        go.countDown();

        assertThat(List.of(a.join(), b.join())).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE username = ?", Integer.class, name))
                .isEqualTo(1);
    }

    private HttpStatus claimOnSignal(CountDownLatch go, Member member, String name) {
        try {
            go.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
        return HttpStatus.valueOf(
                put(PATH, member.token(), new UsernameRequest(name), String.class).getStatusCode().value());
    }

    @Test
    void registrationRefusesBlockedAndHeldNames() {
        String old = freshName();
        Member former = newMember(old);
        changeOk(former, freshName());

        ResponseEntity<ProblemView> blocked = postFromNewClient("/api/v1/auth/register",
                new RegisterRequest("An", "Tran", "admin", uniqueEmail("reg-blocked"), PASSWORD),
                ProblemView.class);
        ResponseEntity<ProblemView> held = postFromNewClient("/api/v1/auth/register",
                new RegisterRequest("An", "Tran", old, uniqueEmail("reg-held"), PASSWORD), ProblemView.class);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(blocked.getBody().code()).isEqualTo("USERNAME_NOT_ALLOWED");
        assertThat(held.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email LIKE 'reg-%'", Integer.class))
                .as("no account was written").isZero();
    }

    @Test
    void firstPasswordSetupRefusesABlockedName() {
        String email = uniqueEmail("setup-blocked");
        assertThat(postFromNewClient("/api/v1/auth/register",
                new RegisterRequest("An", "Tran", null, email, PASSWORD), String.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        String link = mail.lastTokenFor(email.toLowerCase(Locale.ROOT), CONFIRM_LINK);
        ConfirmedView confirmed = postFromNewClient("/api/v1/auth/confirm-email", new ConfirmRequest(link),
                ConfirmedView.class).getBody();

        ResponseEntity<ProblemView> refused = put("/api/v1/me/password", confirmed.accessToken(),
                new PasswordRequest(confirmed.passwordSetupToken(), "new-password-1", "Binh", "Le", "admin"),
                ProblemView.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().code()).isEqualTo("USERNAME_NOT_ALLOWED");
        assertThat(login(email, "new-password-1")).as("no password was set").isNull();

        ResponseEntity<String> retried = put("/api/v1/me/password", confirmed.accessToken(),
                new PasswordRequest(confirmed.passwordSetupToken(), "new-password-1", "Binh", "Le", freshName()),
                String.class);
        assertThat(retried.getStatusCode()).as("the refusal left the setup token unspent").isEqualTo(HttpStatus.OK);
    }

    @Test
    void secondChangeInsideTheLockIs422WithNextChangeAt() {
        Member member = newMember(freshName());
        changeOk(member, freshName());

        ResponseEntity<ProblemView> second = change(member, freshName());

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(second.getBody().code()).isEqualTo("USERNAME_CHANGE_TOO_SOON");
        assertThat(second.getBody().nextChangeAt()).isAfter(Instant.now().plusSeconds(29L * 24 * 3600));
        ProfileView profile = get("/api/v1/auth/profile", member.token(), ProfileView.class).getBody();
        assertThat(profile.usernameNextChangeAt()).isEqualTo(second.getBody().nextChangeAt());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Mail {

        @Bean
        CapturingMailSender capturingMailSender(ObjectProvider<ThreadPoolTaskExecutor> executor) {
            return new CapturingMailSender(executor);
        }
    }
}
