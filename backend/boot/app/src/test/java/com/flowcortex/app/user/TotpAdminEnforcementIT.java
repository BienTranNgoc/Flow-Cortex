package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The admin decision over real HTTP: a console account whose session has not passed the second factor is refused
 * on governed routes, an end user keeps its plain refusal, and the switch lifts the step.
 */
@TotpIt
class TotpAdminEnforcementIT extends AbstractTotpIT {

    private static final String RUNS = "/api/v1/admin/runs";
    private static final String SUPPORT_REQUESTS = "/api/v1/admin/support/requests";
    private static final String KNOWLEDGE_BASE = "/api/v1/admin/support/kb/articles";
    private static final String PROBLEMS = "https://flow-cortex.dev/problems/";

    private static void assertProblem(Reply reply, String slug) {
        assertThat(reply.status()).as("%s", reply.body()).isEqualTo(403);
        assertThat(reply.text("type")).isEqualTo(PROBLEMS + slug);
    }

    @Test
    void superAdminWithoutASecondFactor() {
        Account root = admin();
        Session passwordOnly = passwordSession(root.email());

        Reply refused = get(RUNS, passwordOnly.accessToken());

        assertProblem(refused, "mfa-required");
        assertThat(refused.body().get("enrolled").asBoolean()).isFalse();
        assertThat(refused.text("detail")).isEqualTo("Finish two-factor authentication to use the admin console.");
        assertThat(get(RUNS, enroll(root).session().accessToken()).status())
                .as("the session enable opens has passed a code").isEqualTo(200);
    }

    @Test
    void anEnrolledAccountWithAPasswordOnlyTokenIsToldItIsEnrolled() {
        Account root = admin();
        Session passwordOnly = passwordSession(root.email());
        enroll(root);

        Reply refused = get(RUNS, passwordOnly.accessToken());

        assertProblem(refused, "mfa-required");
        assertThat(refused.body().get("enrolled").asBoolean()).isTrue();
    }

    @Test
    void staffSessionThatPassedTotp() {
        Enrolment linh = enroll(staff());

        Reply afterEnable = get(SUPPORT_REQUESTS, linh.session().accessToken());
        Reply signedIn = complete(challengedLogin(linh.account().email()), freshCode(linh.secret()));
        Reply afterSignIn = get(SUPPORT_REQUESTS, signedIn.text("accessToken"));
        Reply afterRefresh = get(SUPPORT_REQUESTS, refresh(signedIn.cookie(REFRESH_COOKIE)).text("accessToken"));

        assertThat(afterEnable.status()).isEqualTo(200);
        assertThat(hasMfaClaim(signedIn.text("accessToken"))).isTrue();
        assertThat(afterSignIn.status()).isEqualTo(200);
        assertThat(afterRefresh.status()).as("the refreshed token keeps the mark").isEqualTo(200);
    }

    @Test
    void memberWithoutASecondFactorStaysForbidden() {
        Account member = member();

        Reply refused = get(RUNS, passwordSession(member.email()).accessToken());

        assertProblem(refused, "forbidden");
    }

    @Test
    void deactivatedAdminIsToldFirst() {
        Account linh = staff();
        Session passwordOnly = passwordSession(linh.email());
        jdbc.update("UPDATE app_user SET admin_disabled_at = now(), admin_disabled_by = ? WHERE id = ?",
                linh.admin().id(), linh.id());

        Reply refused = get(SUPPORT_REQUESTS, passwordOnly.accessToken());

        assertProblem(refused, "admin-disabled");
    }

    @Test
    void staffWithoutTheKey() {
        Account linh = staff();
        Session passwordOnly = passwordSession(linh.email());
        Reply beforeTotp = get(KNOWLEDGE_BASE, passwordOnly.accessToken());
        Enrolment enrolled = enroll(linh);

        Reply afterTotp = get(KNOWLEDGE_BASE, enrolled.session().accessToken());

        assertProblem(beforeTotp, "mfa-required");
        assertProblem(afterTotp, "forbidden");
    }

    @Test
    void chatAndMemberRoutesStayOpen() {
        Account root = admin();

        Reply workflows = get("/api/v1/workflows", passwordSession(root.email()).accessToken());

        assertThat(workflows.status()).isEqualTo(200);
    }

    @Test
    void switchOff() {
        requirement.set(false);
        Account linh = staff();
        Account root = admin();

        Reply staffRoute = get(SUPPORT_REQUESTS, passwordSession(linh.email()).accessToken());
        Reply adminRoute = get(RUNS, passwordSession(root.email()).accessToken());
        Reply me = get("/api/v1/auth/me", passwordSession(linh.email()).accessToken());

        assertThat(staffRoute.status()).isEqualTo(200);
        assertThat(adminRoute.status()).isEqualTo(200);
        assertThat(me.body().get("totp").get("required").asBoolean()).isFalse();
    }
}
