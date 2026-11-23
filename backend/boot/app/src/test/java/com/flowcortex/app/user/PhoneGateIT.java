package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;

import com.flowcortex.user.api.UserView;
import com.flowcortex.user.phone.PhoneGateConfig;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowService;
import com.flowcortex.workflow.api.WorkflowView;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The phone wall against the real database, with the gate and phone verification on. Every case uses
 * its own accounts; the gate reads the account row and the number row on each request, so a case that
 * verifies a number does it with an insert and keeps the same token.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "flowcortex.phone-gate.enabled=true",
        "flowcortex.phone-verification.enabled=true",
        "flowcortex.sms.sender=outbox"})
class PhoneGateIT extends AbstractUserModuleIT {

    private static final String HELD_CODE = "PHONE_VERIFICATION_REQUIRED";
    private static final String GATE_FACTS_SQL = "LEFT JOIN user_phone p ON p.user_id = u.id";

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private MeterRegistry meters;

    @MockitoSpyBean
    private JdbcTemplate jdbc;

    record ProblemView(int status, String detail, String code) {
    }

    record MeView(String role, Boolean phoneVerificationRequired) {
    }

    record RunBody(String input) {
    }

    @Test
    void anUnverifiedMemberIsRefused403OnAProductRouteAndNoRunIsCreated() {
        UserView member = newMember("gate-held");
        String token = loginAs(member);
        WorkflowView workflow = workflowService.createWorkflow(member.id(), "gate-" + UUID.randomUUID(), false,
                List.of(new NewWorkflowStep("draft", "echo", null)));
        double blockedBefore = meters.counter("phone_gate.blocked").count();

        ResponseEntity<ProblemView> response = post("/api/v1/workflows/" + workflow.id() + "/runs", token,
                new RunBody("hello"), ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().code()).isEqualTo(HELD_CODE);
        assertThat(response.getBody().detail()).isEqualTo("Verify your phone number to continue.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_run WHERE owner_id = ?", Long.class,
                member.id())).isZero();
        assertThat(meters.counter("phone_gate.blocked").count()).isEqualTo(blockedBefore + 1);
    }

    @Test
    void theRefusalDetailFollowsTheRequestLanguage() {
        String token = loginAs(newMember("gate-held-vi"));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "vi");

        ResponseEntity<ProblemView> response = restTemplate.exchange("/api/v1/workflows", HttpMethod.GET,
                new HttpEntity<>(headers), ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().detail()).isEqualTo("Hãy xác minh số điện thoại để tiếp tục.");
    }

    @Test
    void everyExemptRouteStaysReachableForAHeldMember() {
        String token = loginAs(newMember("gate-exempt"));

        assertThat(get("/api/v1/auth/me", token, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/me/phone", token, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/features", token, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertNotHeld(get("/api/v1/support/settings", token, String.class));
        assertThat(sendCode(token, "09" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000)))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertNotHeld(post("/api/v1/me/phone/verify", token, Map.of("code", "000000"), String.class));
        assertNotHeld(put("/api/v1/me/password", token, Map.of(), String.class));
        assertNotHeld(post("/api/v1/client-errors", token, Map.of(), String.class));
        assertThat(post("/api/v1/auth/logout", token, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void aVerifiedMemberIsServed() {
        UserView member = newMember("gate-verified");
        String token = loginAs(member);
        verifyNumberOf(member);

        assertThat(get("/api/v1/workflows", token, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aSuperAdminWithoutANumberIsServed() {
        UserView admin = userService.createInitialAdmin(uniqueEmail("gate-admin"), "password123", "Admin");
        String token = loginAs(admin);

        assertThat(get("/api/v1/workflows", token, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aStaffAccountWithoutANumberIsServed() {
        UserView admin = userService.createInitialAdmin(uniqueEmail("gate-staff-owner"), "password123", "Owner");
        UserView staff = userService.createUser(admin.id(), uniqueEmail("gate-staff"), "password123", "Support",
                "CUSTOMER_SUPPORT");
        String token = loginAs(staff);

        assertThat(get("/api/v1/workflows", token, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void verifyingInAnotherTabLiftsTheGateOnTheSameToken() {
        UserView member = newMember("gate-other-tab");
        String token = loginAs(member);
        assertThat(get("/api/v1/workflows", token, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        verifyNumberOf(member);

        assertThat(get("/api/v1/workflows", token, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anAnonymousCallStill401sOnAProtectedRoute() {
        assertThat(get("/api/v1/workflows", null, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/features", null, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aValidTokenOfADeletedAccountIsNotRefusedByTheGate() {
        UserView admin = userService.createInitialAdmin(uniqueEmail("gate-gone-owner"), "password123", "Owner");
        UserView member = userService.createUser(admin.id(), uniqueEmail("gate-gone"), "password123", "Member",
                "MEMBER");
        String token = loginAs(member);
        userService.deleteUser(admin.id(), member.id());

        ResponseEntity<String> response = get("/api/v1/workflows", token, String.class);

        assertThat(response.getStatusCode().value()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertNotHeld(response);
    }

    @Test
    void meReportsPhoneVerificationRequired() {
        UserView held = newMember("gate-me-held");
        UserView verified = newMember("gate-me-verified");
        verifyNumberOf(verified);
        UserView admin = userService.createInitialAdmin(uniqueEmail("gate-me-admin"), "password123", "Admin");

        MeView heldMe = get("/api/v1/auth/me", loginAs(held), MeView.class).getBody();
        MeView verifiedMe = get("/api/v1/auth/me", loginAs(verified), MeView.class).getBody();
        MeView adminMe = get("/api/v1/auth/me", loginAs(admin), MeView.class).getBody();

        assertThat(heldMe.phoneVerificationRequired()).isTrue();
        assertThat(verifiedMe.phoneVerificationRequired()).isFalse();
        assertThat(adminMe.role()).isEqualTo("ADMIN");
        assertThat(adminMe.phoneVerificationRequired()).isFalse();
    }

    @Test
    void aGatedRequestCostsOneStatementForTheGate() {
        UserView member = newMember("gate-budget");
        String token = loginAs(member);
        verifyNumberOf(member);
        clearInvocations(jdbc);

        assertThat(get("/api/v1/workflows", token, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(gateStatements()).isEqualTo(1);
    }

    @Test
    void startupFailsWhenTheGateIsOnAndVerificationOff() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PhoneGateConfig.class);

        runner.withPropertyValues("flowcortex.phone-gate.enabled=true", "flowcortex.phone-verification.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("flowcortex.phone-gate.enabled")
                            .hasMessageContaining("flowcortex.phone-verification.enabled");
                });
        runner.withPropertyValues("flowcortex.phone-gate.enabled=true", "flowcortex.phone-verification.enabled=true")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("flowcortex.phone-gate.enabled=false", "flowcortex.phone-verification.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Nested
    @TestPropertySource(properties = "flowcortex.phone-gate.enabled=false")
    class WithTheGateOff {

        @Test
        void meReportsFalseAndTheGateReadsNothing() {
            String token = loginAs(newMember("gate-off"));
            clearInvocations(jdbc);

            MeView me = get("/api/v1/auth/me", token, MeView.class).getBody();
            ResponseEntity<String> product = get("/api/v1/workflows", token, String.class);

            assertThat(me.phoneVerificationRequired()).isFalse();
            assertThat(product.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(gateStatements()).isZero();
        }
    }

    /** Calls to the gate's own entry point on {@code JdbcTemplate}; its internal delegations take other overloads. */
    private long gateStatements() {
        return mockingDetails(jdbc).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("query"))
                .filter(call -> call.getMethod().getParameterTypes().length == 3
                        && call.getMethod().getParameterTypes()[1] == RowMapper.class)
                .filter(call -> call.getArgument(0) instanceof String sql && sql.contains(GATE_FACTS_SQL))
                .count();
    }

    private UserView newMember(String prefix) {
        UserView admin = userService.createInitialAdmin(uniqueEmail(prefix + "-admin"), "password123", "Admin");
        return userService.createUser(admin.id(), uniqueEmail(prefix), "password123", "Member", "MEMBER");
    }

    private String loginAs(UserView user) {
        String token = login(user.email(), "password123");
        assertThat(token).as("login of %s", user.email()).isNotNull();
        return token;
    }

    private void verifyNumberOf(UserView member) {
        String e164 = "+849" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        jdbc.update("INSERT INTO user_phone (user_id, phone_e164, verified_at) VALUES (?, ?, now())", member.id(),
                e164);
    }

    private ResponseEntity<String> sendCode(String token, String phone) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Forwarded-For", "198.51.100.77");
        return restTemplate.exchange("/api/v1/me/phone/send-code", HttpMethod.POST,
                new HttpEntity<>(Map.of("phone", phone), headers), String.class);
    }

    private static void assertNotHeld(ResponseEntity<String> response) {
        assertThat(response.getBody() == null ? "" : response.getBody()).doesNotContain(HELD_CODE);
    }
}
