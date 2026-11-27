package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Setting up, confirming, reporting, regenerating and turning off the second factor over real HTTP and the real
 * database. Codes are computed with the code the server uses, at the test clock's instant.
 */
@TotpIt
@ExtendWith(OutputCaptureExtension.class)
class TotpEnrollmentIT extends AbstractTotpIT {

    private static final String TOTP = "/api/v1/auth/totp";
    private static final String RECOVERY_SHAPE = "[A-Z2-7]{5}-[A-Z2-7]{5}";

    private static List<String> recoveryCodes(Reply reply) {
        List<String> codes = new ArrayList<>();
        reply.body().get("recoveryCodes").forEach(code -> codes.add(code.asString()));
        return codes;
    }

    @Test
    void firstSetup() {
        Account member = member();
        Session session = passwordSession(member.email());

        Reply reply = setup(session);

        assertThat(reply.status()).isEqualTo(200);
        String secret = reply.text("secret");
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat(reply.text("otpauthUri")).startsWith("otpauth://totp/Nodi:" + member.email() + "?secret=" + secret)
                .contains("issuer=Nodi");
        assertThat(reply.text("qrDataUrl")).startsWith("data:image/svg+xml");
        assertThat(reply.headers().getCacheControl()).contains("no-store");
        Reply status = get(TOTP, session.accessToken());
        assertThat(status.body().get("pending").asBoolean()).isTrue();
        assertThat(status.body().get("enabled").asBoolean()).isFalse();
        assertThat(status.body().toString()).as("no later response returns the secret").doesNotContain(secret);
        assertThat((String) totpRow(member.id()).get("secret_encrypted")).as("sealed at rest")
                .startsWith("k1.").doesNotContain(secret);
    }

    @Test
    void setupAgainBeforeConfirming() {
        Account member = member();
        Session session = passwordSession(member.email());
        String first = setup(session).text("secret");
        String second = setup(session).text("secret");
        assertThat(second).isNotEqualTo(first);

        Reply wrong = enable(session, currentCode(first));
        Reply right = enable(session, currentCode(second));

        assertThat(wrong.status()).isEqualTo(422);
        assertThat(wrong.text("code")).isEqualTo("TOTP_INVALID_CODE");
        assertThat(right.status()).isEqualTo(200);
        assertThat(totpRow(member.id()).get("failed_attempts")).isEqualTo(0);
    }

    @Test
    void setupWhileEnabled() {
        Enrolment enrolment = enroll(member());
        Object before = totpRow(enrolment.account().id()).get("secret_encrypted");

        Reply again = setup(enrolment.session());

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.text("code")).isEqualTo("TOTP_ALREADY_ENABLED");
        assertThat(totpRow(enrolment.account().id()).get("secret_encrypted")).isEqualTo(before);
        Reply stillWorks = post(TOTP + "/recovery-codes", enrolment.session().accessToken(),
                Map.of("code", freshCode(enrolment.secret())));
        assertThat(stillWorks.status()).isEqualTo(200);
    }

    @Test
    void enableWithACorrectCode() {
        Account member = member();
        Session first = passwordSession(member.email());
        Session other = passwordSession(member.email());
        String secret = setup(first).text("secret");

        Reply enabled = enable(first, currentCode(secret));

        assertThat(enabled.status()).isEqualTo(200);
        List<String> codes = recoveryCodes(enabled);
        assertThat(codes).hasSize(10).doesNotHaveDuplicates().allMatch(code -> code.matches(RECOVERY_SHAPE));
        assertThat(enabled.headers().getCacheControl()).contains("no-store");
        assertThat(hasMfaClaim(enabled.text("accessToken"))).isTrue();
        assertThat(enabled.cookie(REFRESH_COOKIE)).isNotBlank();
        assertThat(refresh(other.refreshCookie()).status()).as("another session is refused").isEqualTo(401);
        assertThat(refresh(first.refreshCookie()).status()).as("the presented session is replaced").isEqualTo(401);
        Reply refreshed = refresh(enabled.cookie(REFRESH_COOKIE));
        assertThat(refreshed.status()).isEqualTo(200);
        assertThat(hasMfaClaim(refreshed.text("accessToken"))).as("the new session keeps its mark").isTrue();
        assertThat(eventCount("TOTP_ENABLED", member.id())).isEqualTo(1);
        assertThat(totpRow(member.id()).get("enabled_at")).isNotNull();
    }

    @Test
    void enableWithoutSetup() {
        Session session = passwordSession(member().email());

        Reply reply = enable(session, "123456");

        assertThat(reply.status()).isEqualTo(409);
        assertThat(reply.text("code")).isEqualTo("TOTP_NOT_SET_UP");
    }

    @Test
    void twoEnablesAtOnce() throws Exception {
        Account member = member();
        Session session = passwordSession(member.email());
        String code = currentCode(setup(session).text("secret"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Callable<Reply> enableOnGo = () -> {
                go.await();
                return enable(session, code);
            };
            Future<Reply> first = pool.submit(enableOnGo);
            Future<Reply> second = pool.submit(enableOnGo);
            go.countDown();
            List<Reply> replies = List.of(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS));

            assertThat(replies).extracting(Reply::status).containsExactlyInAnyOrder(200, 409);
            Reply winner = replies.stream().filter(reply -> reply.status() == 200).findFirst().orElseThrow();
            Reply loser = replies.stream().filter(reply -> reply.status() == 409).findFirst().orElseThrow();
            assertThat(loser.text("code")).isEqualTo("TOTP_ALREADY_ENABLED");
            assertThat(recoveryCodesInDatabase(member.id())).isEqualTo(10);
            clock.advance(java.time.Duration.ofSeconds(61));
            Reply signIn = complete(challengedLogin(member.email()), recoveryCodes(winner).get(0));
            assertThat(signIn.status()).as("the winner's codes work").isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void staffAccountNotEnrolled() {
        Account linh = staff();
        Account customer = member();

        JsonNode staffState = get("/api/v1/auth/me", passwordSession(linh.email()).accessToken()).body().get("totp");
        JsonNode memberState = get("/api/v1/auth/me", passwordSession(customer.email()).accessToken()).body()
                .get("totp");

        assertThat(staffState.get("enabled").asBoolean()).isFalse();
        assertThat(staffState.get("required").asBoolean()).isTrue();
        assertThat(staffState.get("verified").asBoolean()).isFalse();
        assertThat(memberState.get("required").asBoolean()).isFalse();
    }

    @Test
    void codesLeftAfterOneUse() {
        Enrolment enrolment = enroll(member());

        Reply signedIn = complete(challengedLogin(enrolment.account().email()), enrolment.recoveryCodes().get(0));
        Reply status = get(TOTP, signedIn.text("accessToken"));

        assertThat(signedIn.status()).isEqualTo(200);
        assertThat(status.body().get("recoveryCodesLeft").asInt()).isEqualTo(9);
        assertThat(status.body().get("enabled").asBoolean()).isTrue();
        assertThat(status.body().get("verified").asBoolean()).isTrue();
        assertThat(eventCount("TOTP_RECOVERY_CODE_USED", enrolment.account().id())).isEqualTo(1);
    }

    @Test
    void memberTurnsItOff() {
        Enrolment enrolment = enroll(member());
        Account member = enrolment.account();

        Reply off = post(TOTP + "/disable", enrolment.session().accessToken(),
                Map.of("code", freshCode(enrolment.secret())));

        assertThat(off.status()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_totp WHERE user_id = ?", Integer.class,
                member.id())).isZero();
        assertThat(recoveryCodesInDatabase(member.id())).as("the old recovery codes are gone").isZero();
        assertThat(eventCount("TOTP_DISABLED", member.id())).isEqualTo(1);
        Reply login = passwordLogin(member.email(), false, false);
        assertThat(login.status()).as("the next sign-in opens a session directly").isEqualTo(200);
        assertThat(login.cookie(CHALLENGE_COOKIE)).isNull();
    }

    @Test
    void adminCannotTurnItOff() {
        Enrolment enrolment = enroll(admin());

        Reply off = post(TOTP + "/disable", enrolment.session().accessToken(),
                Map.of("code", freshCode(enrolment.secret())));

        assertThat(off.status()).isEqualTo(409);
        assertThat(off.text("code")).isEqualTo("TOTP_REQUIRED_FOR_ROLE");
        assertThat(get(TOTP, enrolment.session().accessToken()).body().get("enabled").asBoolean()).isTrue();
    }

    @Test
    void regenerate() {
        Enrolment enrolment = enroll(member());

        Reply fresh = post(TOTP + "/recovery-codes", enrolment.session().accessToken(),
                Map.of("code", freshCode(enrolment.secret())));

        assertThat(fresh.status()).isEqualTo(200);
        assertThat(fresh.headers().getCacheControl()).contains("no-store");
        List<String> codes = recoveryCodes(fresh);
        assertThat(codes).hasSize(10).doesNotContainAnyElementsOf(enrolment.recoveryCodes());
        assertThat(recoveryCodesInDatabase(enrolment.account().id())).isEqualTo(10);
        Reply login = challengedLogin(enrolment.account().email());
        Reply earlier = complete(login, enrolment.recoveryCodes().get(0));
        assertThat(earlier.status()).as("an earlier code is refused").isEqualTo(422);
        assertThat(earlier.text("code")).isEqualTo("TOTP_INVALID_CODE");
        assertThat(complete(login, codes.get(0)).status()).as("a new code is accepted").isEqualTo(200);
        assertThat(eventCount("TOTP_RECOVERY_CODES_REGENERATED", enrolment.account().id())).isEqualTo(1);
    }

    @Test
    void regenerateWithARecoveryCode() {
        Enrolment enrolment = enroll(member());
        String sql = "SELECT code_hash FROM user_recovery_code WHERE user_id = ? ORDER BY code_hash";
        List<String> before = jdbc.queryForList(sql, String.class, enrolment.account().id());

        Reply reply = post(TOTP + "/recovery-codes", enrolment.session().accessToken(),
                Map.of("code", enrolment.recoveryCodes().get(0)));

        assertThat(reply.status()).isEqualTo(422);
        assertThat(reply.text("code")).isEqualTo("TOTP_INVALID_CODE");
        assertThat(jdbc.queryForList(sql, String.class, enrolment.account().id())).isEqualTo(before);
    }

    @Test
    void switchOffLetsAnAdminDisable() {
        requirement.set(false);
        Enrolment enrolment = enroll(admin());

        JsonNode state = get(TOTP, enrolment.session().accessToken()).body();
        Reply off = post(TOTP + "/disable", enrolment.session().accessToken(),
                Map.of("code", freshCode(enrolment.secret())));

        assertThat(state.get("required").asBoolean()).isFalse();
        assertThat(off.status()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_totp WHERE user_id = ?", Integer.class,
                enrolment.account().id())).isZero();
    }

    @Test
    void noEventCarriesACode(CapturedOutput output) {
        Enrolment enrolment = enroll(member());
        String email = enrolment.account().email();
        Reply first = challengedLogin(email);
        String wrong = wrongCode(enrolment.secret());
        complete(first, wrong);
        String right = freshCode(enrolment.secret());
        complete(first, right);
        Reply second = challengedLogin(email);
        complete(second, enrolment.recoveryCodes().get(0));
        String last = freshCode(enrolment.secret());
        post(TOTP + "/disable", enrolment.session().accessToken(), Map.of("code", last));

        Set<String> longSecrets = new HashSet<>(enrolment.recoveryCodes());
        enrolment.recoveryCodes().forEach(code -> longSecrets.add(code.replace("-", "")));
        longSecrets.add(enrolment.secret());
        longSecrets.add(first.cookie(CHALLENGE_COOKIE));
        longSecrets.add(second.cookie(CHALLENGE_COOKIE));
        List<String> metadata = jdbc.queryForList("SELECT metadata::text FROM auth_event WHERE user_id = ?",
                String.class, enrolment.account().id());
        Set<String> values = new HashSet<>();
        JsonMapper json = JsonMapper.builder().build();
        for (String row : metadata) {
            json.readTree(row).forEach(node -> values.add(node.asString()));
        }

        assertThat(metadata).isNotEmpty();
        assertThat(values).doesNotContain(wrong, right, last, enrolment.secret());
        for (String secret : longSecrets) {
            assertThat(String.join(" ", metadata)).doesNotContain(secret);
            assertThat(output.getAll()).doesNotContain(secret);
        }
    }
}
