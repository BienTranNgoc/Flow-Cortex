package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.flowcortex.user.application.AccountCredentials;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/**
 * The reset of another account's second factor over real HTTP: who may reach whom, what the reset removes and
 * revokes, that a failure changes nothing, and that a console session without a second factor cannot mint a
 * personal access token.
 */
@TotpIt
class TotpResetIT extends AbstractTotpIT {

    private static final String PROBLEMS = "https://flow-cortex.dev/problems/";
    private static final String SUPPORT_REQUESTS = "/api/v1/admin/support/requests";

    @Autowired
    private AccountCredentials accountCredentials;

    private static String resetPath(UUID id) {
        return "/api/v1/users/" + id + "/totp/reset";
    }

    /** An account of {@code role} owned by the super admin {@code root}. */
    private Account under(Account root, String role) {
        String email = uniqueEmail("totp-" + role.toLowerCase().replace('_', '-'));
        return new Account(root.user(), userService.createUser(root.id(), email, PASSWORD, "Linh", role), email);
    }

    private Reply reset(Session caller, UUID target) {
        return post(resetPath(target), caller.accessToken(), null);
    }

    /** Grants {@code key} to {@code role}; true when this call created the row, so only then is it removed. */
    private boolean grant(String role, String key) {
        return jdbc.update("INSERT INTO role_permission (role_code, permission_key) VALUES (?, ?)"
                + " ON CONFLICT DO NOTHING", role, key) == 1;
    }

    private void withdraw(boolean granted, String role, String key) {
        if (granted) {
            jdbc.update("DELETE FROM role_permission WHERE role_code = ? AND permission_key = ?", role, key);
        }
    }

    private int liveChallenges(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM login_challenge WHERE user_id = ? AND spent_at IS NULL",
                Integer.class, userId);
    }

    private boolean totpIsOn(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM user_totp WHERE user_id = ? AND enabled_at IS NOT NULL",
                Integer.class, userId) == 1;
    }

    private String createToken(Account account, Session session) {
        Reply created = post("/api/v1/me/api-tokens", session.accessToken(),
                Map.of("name", "CI runner", "stepUpProof", proof(account, session)));
        assertThat(created.status()).as("create token: %s", created.body()).isEqualTo(201);
        return created.text("token");
    }

    private String proof(Account account, Session session) {
        Reply step = post("/api/v1/me/step-up", session.accessToken(),
                Map.of("password", PASSWORD, "purpose", "PAT_CREATE"));
        assertThat(step.status()).as("step-up of %s: %s", account.email(), step.body()).isEqualTo(200);
        return step.text("stepUpProof");
    }

    @Test
    void lostDeviceOfAStaffAccount() {
        Account root = admin();
        Enrolment s = enroll(root);
        Enrolment linh = enroll(under(root, "CUSTOMER_SUPPORT"));
        UUID id = linh.account().id();

        Reply reply = reset(s.session(), id);

        assertThat(reply.status()).as("%s", reply.body()).isEqualTo(204);
        assertThat(refresh(linh.session().refreshCookie()).status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_totp WHERE user_id = ?", Integer.class, id))
                .isZero();
        assertThat(recoveryCodesInDatabase(id)).isZero();
        Reply user = get("/api/v1/users/" + id, s.session().accessToken());
        assertThat(user.body().get("totpEnabled").asBoolean()).isFalse();
        Reply next = passwordLogin(linh.account().email(), false, false);
        assertThat(next.status()).as("the next sign-in is one step: %s", next.body()).isEqualTo(200);
        assertThat(hasMfaClaim(next.text("accessToken"))).isFalse();
        Reply console = get(SUPPORT_REQUESTS, next.text("accessToken"));
        assertThat(console.status()).isEqualTo(403);
        assertThat(console.text("type")).isEqualTo(PROBLEMS + "mfa-required");
        assertThat(console.body().get("enrolled").asBoolean()).isFalse();
        assertThat(eventCount("TOTP_RESET", id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT metadata::text FROM auth_event WHERE type = 'TOTP_RESET'"
                + " AND user_id = ?", String.class, id)).contains(s.account().id().toString());
    }

    @Test
    void lostDeviceOfAnAdminAccountRevokesItsTokens() {
        Account root = admin();
        Enrolment s = enroll(root);
        Enrolment binh = enroll(under(root, "ADMIN"));
        String token = createToken(binh.account(), binh.session());
        assertThat(get("/api/v1/auth/me", token).status()).as("the token works before the reset").isEqualTo(200);

        Reply reply = reset(s.session(), binh.account().id());

        assertThat(reply.status()).as("%s", reply.body()).isEqualTo(204);
        assertThat(get("/api/v1/auth/me", token).status()).as("the token is refused after it").isEqualTo(401);
        assertThat(refresh(binh.session().refreshCookie()).status()).isEqualTo(401);
    }

    @Test
    void supportAgentResetsAnAdmin() {
        Account root = admin();
        Enrolment binh = enroll(under(root, "ADMIN"));
        Enrolment manager = enroll(under(root, "OPERATION_MANAGER"));
        boolean granted = grant("OPERATION_MANAGER", "user.account.update");
        try {
            Reply reply = reset(manager.session(), binh.account().id());

            assertThat(reply.status()).isEqualTo(403);
            assertThat(reply.text("type")).isEqualTo(PROBLEMS + "forbidden");
            assertThat(totpIsOn(binh.account().id())).isTrue();
            assertThat(recoveryCodesInDatabase(binh.account().id())).isEqualTo(10);
            assertThat(refresh(binh.session().refreshCookie()).status()).isEqualTo(200);
        } finally {
            withdraw(granted, "OPERATION_MANAGER", "user.account.update");
        }
    }

    @Test
    void staffWithTheKeyResetsAMember() {
        Account root = admin();
        Enrolment linh = enroll(under(root, "CUSTOMER_SUPPORT"));
        Enrolment member = enroll(under(root, "MEMBER"));
        Session passwordOnly = passwordSession(under(root, "CUSTOMER_SUPPORT").email());
        boolean granted = grant("CUSTOMER_SUPPORT", "user.account.update");
        try {
            Reply withoutSecondFactor = reset(passwordOnly, member.account().id());
            Reply reply = reset(linh.session(), member.account().id());

            assertThat(withoutSecondFactor.status()).isEqualTo(403);
            assertThat(withoutSecondFactor.text("type")).isEqualTo(PROBLEMS + "mfa-required");
            assertThat(reply.status()).as("%s", reply.body()).isEqualTo(204);
            assertThat(totpIsOn(member.account().id())).isFalse();
        } finally {
            withdraw(granted, "CUSTOMER_SUPPORT", "user.account.update");
        }
    }

    @Test
    void resetDuringChallenge() {
        Account root = admin();
        Enrolment s = enroll(root);
        Enrolment linh = enroll(under(root, "CUSTOMER_SUPPORT"));
        Reply login = challengedLogin(linh.account().email());
        assertThat(liveChallenges(linh.account().id())).isEqualTo(1);

        assertThat(reset(s.session(), linh.account().id()).status()).isEqualTo(204);
        Reply completed = complete(login, freshCode(linh.secret()));

        assertThat(completed.status()).isEqualTo(401);
        assertThat(completed.text("code")).isEqualTo("TOTP_CHALLENGE_INVALID");
        assertThat(completed.cookie(REFRESH_COOKIE)).isNull();
        assertThat(liveChallenges(linh.account().id())).isZero();
    }

    @Test
    void rootAdminIsOutOfReach() {
        Account root = admin();
        Enrolment r = enroll(root);
        Enrolment b = enroll(under(root, "ADMIN"));

        Reply reply = reset(b.session(), root.id());

        assertThat(reply.status()).isEqualTo(404);
        assertThat(totpIsOn(root.id())).isTrue();
        assertThat(refresh(r.session().refreshCookie()).status()).isEqualTo(200);
    }

    @Test
    void ownReset() {
        Enrolment s = enroll(admin());

        Reply reply = reset(s.session(), s.account().id());

        assertThat(reply.status()).isEqualTo(422);
        assertThat(reply.text("code")).isEqualTo("TOTP_RESET_SELF");
        assertThat(reply.text("detail")).isEqualTo("You cannot reset your own two-factor authentication.");
        assertThat(totpIsOn(s.account().id())).isTrue();
        assertThat(recoveryCodesInDatabase(s.account().id())).isEqualTo(10);
    }

    @Test
    void targetWithoutTotp() {
        Account root = admin();
        Enrolment s = enroll(root);
        Account plain = under(root, "MEMBER");
        Account pending = under(root, "MEMBER");
        assertThat(setup(passwordSession(pending.email())).status()).isEqualTo(200);

        Reply none = reset(s.session(), plain.id());
        Reply notConfirmed = reset(s.session(), pending.id());

        assertThat(none.status()).isEqualTo(409);
        assertThat(none.text("code")).isEqualTo("TOTP_NOT_ENABLED");
        assertThat(notConfirmed.status()).isEqualTo(409);
        assertThat(notConfirmed.text("code")).isEqualTo("TOTP_NOT_ENABLED");
        assertThat(totpRow(pending.id()).get("enabled_at")).as("a pending setup is left alone").isNull();
    }

    @Test
    void unknownId() {
        Enrolment s = enroll(admin());

        Reply reply = reset(s.session(), UUID.randomUUID());

        assertThat(reply.status()).isEqualTo(404);
    }

    @Test
    void resetFailureLeavesTheTargetUnchanged() {
        Account root = admin();
        Enrolment s = enroll(root);
        Enrolment linh = enroll(under(root, "CUSTOMER_SUPPORT"));
        challengedLogin(linh.account().email());
        UUID id = linh.account().id();
        doThrow(new IllegalStateException("revoke failed")).when(accountCredentials).revokeAllFor(any());

        Reply reply = reset(s.session(), id);

        assertThat(reply.status()).isEqualTo(500);
        assertThat(totpIsOn(id)).isTrue();
        assertThat(recoveryCodesInDatabase(id)).isEqualTo(10);
        assertThat(liveChallenges(id)).isEqualTo(1);
        assertThat(eventCount("TOTP_RESET", id)).isZero();
        assertThat(refresh(linh.session().refreshCookie()).status()).isEqualTo(200);
    }

    @Test
    void adminSessionWithoutASecondFactorCannotCreateAToken() {
        Account root = admin();
        Session passwordOnly = passwordSession(root.email());
        String proof = proof(root, passwordOnly);

        Reply refused = post("/api/v1/me/api-tokens", passwordOnly.accessToken(),
                Map.of("name", "CI runner", "stepUpProof", proof));

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.text("code")).isEqualTo("PAT_MFA_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_access_token WHERE user_id = ?", Integer.class,
                root.id())).isZero();
        requirement.set(false);
        Reply withTheSameProof = post("/api/v1/me/api-tokens", passwordOnly.accessToken(),
                Map.of("name", "CI runner", "stepUpProof", proof));
        assertThat(withTheSameProof.status()).as("the refusal kept the proof: %s", withTheSameProof.body())
                .isEqualTo(201);
    }

    @Test
    void adminSessionThatPassedTotpCreatesAToken() {
        Enrolment s = enroll(admin());

        String token = createToken(s.account(), s.session());

        assertThat(token).startsWith("fc_pat_");
        assertThat(call(HttpMethod.GET, "/api/v1/auth/me", token, null, Map.of(), false).status()).isEqualTo(200);
    }
}
