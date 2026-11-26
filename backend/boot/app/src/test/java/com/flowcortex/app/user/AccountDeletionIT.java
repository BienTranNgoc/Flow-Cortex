package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import com.flowcortex.app.user.AccountDeletionIT.Clocks;
import com.flowcortex.settings.api.Settings;
import com.flowcortex.user.accountdeletion.AccountDeletionSettingKeys;
import com.flowcortex.user.api.AccountDeletionBlocker;
import com.flowcortex.user.api.StepUpProofs;
import com.flowcortex.user.api.StepUpPurpose;
import com.flowcortex.user.api.StepUpRequiredException;
import com.flowcortex.user.api.UserView;
import com.flowcortex.user.application.AccountCredentials;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * A member's deletion request, status, cancel and the wall around a pending account, over real HTTP
 * and the real database. The switch is stored on before each case and forgotten after it; the clock
 * only moves forward and is reset after each case. Every case uses its own accounts.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ExtendWith(OutputCaptureExtension.class)
@Import(Clocks.class)
class AccountDeletionIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "password123";
    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String PENDING_CODE = "ACCOUNT_DELETION_PENDING";
    private static final String ENGLISH_PENDING = "Your account is scheduled for deletion.";
    private static final String VIETNAMESE_PENDING = "Tài khoản của bạn đang chờ xóa.";
    private static final String BASE = "/api/v1/me/account-deletion";
    private static final UUID OPERATOR = UUID.randomUUID();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Settings settings;

    @Autowired
    private MovableClock clock;

    @Autowired
    private MeterRegistry meters;

    @MockitoSpyBean
    private AccountCredentials credentials;

    @MockitoSpyBean
    private StepUpProofs stepUpProofs;

    @MockitoSpyBean(name = "openRequestDeletionBlocker")
    private AccountDeletionBlocker openRequestBlocker;

    record ProblemView(int status, String detail, String code, List<ReasonView> reasons) {
    }

    record ReasonView(String key, String message) {
    }

    record AcceptedView(UUID requestId, String state, Instant requestedAt, Instant scheduledAt) {
    }

    record StatusView(Boolean enabled, Integer gracePeriodDays, String state, Instant requestedAt,
            Instant scheduledAt) {
    }

    record CancelledView(String state, Instant cancelledAt) {
    }

    record EligibilityView(Boolean eligible, List<ReasonView> reasons) {
    }

    record DeletionView(String state, Instant requestedAt, Instant scheduledAt) {
    }

    record MeBody(String role, DeletionView accountDeletion) {
    }

    record StepUpView(String stepUpProof, Instant expiresAt) {
    }

    record ProofBody(String stepUpProof) {
    }

    record Member(UUID id, String email, String token) {
    }

    @BeforeEach
    void switchOnWithAThirtyDayGrace() {
        restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        settings.set(AccountDeletionSettingKeys.ENABLED, true, OPERATOR);
        settings.set(AccountDeletionSettingKeys.GRACE_PERIOD_DAYS, 30, OPERATOR);
    }

    @AfterEach
    void restoreTheDefaults() {
        clock.reset();
        forgetSetting(AccountDeletionSettingKeys.ENABLED.name());
        forgetSetting(AccountDeletionSettingKeys.GRACE_PERIOD_DAYS.name());
    }

    @Test
    void requestWithAThirtyDayGrace() {
        Member member = newMember("request");

        ResponseEntity<AcceptedView> response = request(member.token(), proofFor(member), AcceptedView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        AcceptedView accepted = response.getBody();
        assertThat(accepted.state()).isEqualTo("PENDING");
        assertThat(accepted.scheduledAt()).isEqualTo(accepted.requestedAt().plus(Duration.ofDays(30)));
        assertThat(accepted.requestedAt()).isBetween(Instant.now().minusSeconds(30), Instant.now().plusSeconds(30));
        assertThat(requestRows(member.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT scheduled_at FROM account_deletion_request WHERE user_id = ?",
                Timestamp.class, member.id()).toInstant()).isEqualTo(accepted.scheduledAt());
    }

    @Test
    void graceChangedAfterTheRequestKeepsTheDeadline() {
        Member member = newMember("grace-changed");
        AcceptedView accepted = request(member.token(), proofFor(member), AcceptedView.class).getBody();

        settings.set(AccountDeletionSettingKeys.GRACE_PERIOD_DAYS, 7, OPERATOR);

        StatusView status = get(BASE, member.token(), StatusView.class).getBody();
        assertThat(status.scheduledAt()).isEqualTo(accepted.scheduledAt());
        assertThat(status.gracePeriodDays()).isEqualTo(7);
    }

    @Test
    void switchOffByDefaultSpendsNothing() {
        Member member = newMember("switch-off");
        String proof = proofFor(member);
        forgetSetting(AccountDeletionSettingKeys.ENABLED.name());

        ResponseEntity<ProblemView> response = request(member.token(), proof, ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().detail()).isEqualTo("This feature is turned off");
        assertThat(requestRows(member.id())).isZero();
        stepUpProofs.consume(member.id(), StepUpPurpose.ACCOUNT_DELETION, proof);
        StatusView status = get(BASE, member.token(), StatusView.class).getBody();
        assertThat(status.enabled()).isFalse();
        assertThat(status.state()).isEqualTo("NONE");
    }

    @Nested
    @TestPropertySource(properties = "flowcortex.account-deletion.enabled=false")
    class WithTheEnvironmentVeto {

        @Test
        void environmentVeto() {
            Member member = newMember("veto");

            ResponseEntity<ProblemView> response = request(member.token(), proofFor(member), ProblemView.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody().detail()).isEqualTo("This feature is turned off");
            assertThat(requestRows(member.id())).isZero();
        }
    }

    @Test
    void cancelWhileSwitchedOff() {
        Member member = newMember("cancel-off");
        request(member.token(), proofFor(member), AcceptedView.class);
        settings.set(AccountDeletionSettingKeys.ENABLED, false, OPERATOR);

        ResponseEntity<CancelledView> response = post(BASE + "/cancel", member.token(), null, CancelledView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().state()).isEqualTo("CANCELLED");
    }

    @Test
    void graceOutOfBoundsIsRefused() {
        UserView admin = userService.createInitialAdmin(uniqueEmail("grace-admin"), PASSWORD, "Admin");
        String adminToken = loginAs(admin.email());
        String path = "/api/v1/admin/settings/" + AccountDeletionSettingKeys.GRACE_PERIOD_DAYS.name();

        for (int value : new int[] {0, 366}) {
            ResponseEntity<ProblemView> response = put(path, adminToken, Map.of("value", value), ProblemView.class);

            assertThat(response.getStatusCode()).as("value %s", value).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(response.getBody().code()).isEqualTo("setting.invalidValue");
        }
        assertThat(settings.get(AccountDeletionSettingKeys.GRACE_PERIOD_DAYS).effective()).isEqualTo(30);
    }

    @Test
    void staffAccountIsRefusedBeforeTheProof() {
        UserView owner = userService.createInitialAdmin(uniqueEmail("staff-owner"), PASSWORD, "Owner");
        UserView staff = userService.createUser(owner.id(), uniqueEmail("staff"), PASSWORD, "Operator",
                "PLATFORM_OPERATOR");
        Member operator = new Member(staff.id(), staff.email(), loginAs(staff.email()));
        String proof = proofFor(operator);

        ResponseEntity<ProblemView> response = request(operator.token(), proof, ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("account.deletion.staffAccount");
        assertThat(requestRows(staff.id())).isZero();
        stepUpProofs.consume(staff.id(), StepUpPurpose.ACCOUNT_DELETION, proof);
    }

    @Test
    void superAdminIsRefused() {
        UserView admin = userService.createInitialAdmin(uniqueEmail("super"), PASSWORD, "Admin");
        Member superAdmin = new Member(admin.id(), admin.email(), loginAs(admin.email()));

        ResponseEntity<ProblemView> response = request(superAdmin.token(), proofFor(superAdmin), ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("account.deletion.staffAccount");
        assertThat(requestRows(admin.id())).isZero();
    }

    @Test
    void aMissingProofIs403AndWritesNothing() {
        Member member = newMember("no-proof");

        ResponseEntity<ProblemView> unknown = request(member.token(), "never-minted-proof", ProblemView.class);
        ResponseEntity<ProblemView> blank = request(member.token(), " ", ProblemView.class);

        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(unknown.getBody().code()).isEqualTo("STEP_UP_REQUIRED");
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(requestRows(member.id())).isZero();
        assertThat(projectionSet(member.id())).isFalse();
        assertThat(eventCount(member.id(), "ACCOUNT_DELETION_REQUESTED")).isZero();
    }

    @Test
    void aPatCreateProofIsRefused() {
        Member member = newMember("proof-purpose");
        ResponseEntity<StepUpView> minted = stepUp(member, "ACCOUNT_DELETION");
        assertThat(Duration.between(Instant.now(), minted.getBody().expiresAt()))
                .isBetween(Duration.ofSeconds(290), Duration.ofSeconds(310));

        assertThatThrownBy(() -> stepUpProofs.consume(member.id(), StepUpPurpose.PAT_CREATE,
                minted.getBody().stepUpProof())).isInstanceOf(StepUpRequiredException.class);
        ResponseEntity<ProblemView> wrongPurpose = request(member.token(), stepUp(member, "PAT_CREATE").getBody()
                .stepUpProof(), ProblemView.class);
        assertThat(wrongPurpose.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(wrongPurpose.getBody().code()).isEqualTo("STEP_UP_REQUIRED");
        assertThat(requestRows(member.id())).isZero();

        assertThat(request(member.token(), proofFor(member), AcceptedView.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void sessionsAndTokensEnd() {
        Member member = newMember("credentials");
        String laptop = refreshCookieOf(loginRaw(member.email(), PASSWORD));
        String phone = refreshCookieOf(loginRaw(member.email(), PASSWORD));
        UUID tokenId = UUID.randomUUID();
        jdbc.update("INSERT INTO personal_access_token (id, user_id, name, token_hash, created_at, expires_at) "
                + "VALUES (?, ?, 'script', ?, now() - interval '1 hour', now() + interval '30 days')", tokenId,
                member.id(), "hash-" + tokenId);

        AcceptedView accepted = request(member.token(), proofFor(member), AcceptedView.class).getBody();

        assertThat(refresh(laptop).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(phone).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM personal_access_token WHERE id = ?",
                Boolean.class, tokenId)).isTrue();
        assertThat(eventCount(member.id(), "ACCOUNT_DELETION_REQUESTED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT metadata::text FROM auth_event WHERE user_id = ? AND type = ?",
                String.class, member.id(), "ACCOUNT_DELETION_REQUESTED")).contains(accepted.requestId().toString());
    }

    @Test
    void failureLeavesNothing() {
        Member member = newMember("failure");
        String session = refreshCookieOf(loginRaw(member.email(), PASSWORD));
        doThrow(new IllegalStateException("revocation failed")).when(credentials).revokeAllFor(member.id());

        ResponseEntity<ProblemView> response = request(member.token(), proofFor(member), ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(requestRows(member.id())).isZero();
        assertThat(projectionSet(member.id())).isFalse();
        assertThat(eventCount(member.id(), "ACCOUNT_DELETION_REQUESTED")).isZero();
        assertThat(refresh(session).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void doubleSubmitConvergesOnOneRequest() throws Exception {
        Member member = newMember("double");
        String first = proofFor(member);
        String second = proofFor(member);

        List<ResponseEntity<AcceptedView>> answers = runTogether(
                () -> request(member.token(), first, AcceptedView.class),
                () -> request(member.token(), second, AcceptedView.class));

        assertThat(answers).extracting(ResponseEntity::getStatusCode).containsOnly(HttpStatus.ACCEPTED);
        assertThat(answers.get(0).getBody().requestId()).isEqualTo(answers.get(1).getBody().requestId());
        assertThat(answers.get(0).getBody().scheduledAt()).isEqualTo(answers.get(1).getBody().scheduledAt());
        assertThat(requestRows(member.id())).isEqualTo(1);
        assertThat(eventCount(member.id(), "ACCOUNT_DELETION_REQUESTED")).isEqualTo(1);
    }

    @Test
    void twinThatCommitsBeforeTheLoserSpendsItsProofConverges() {
        Member member = newMember("twin-first");
        String first = proofFor(member);
        String second = proofFor(member);
        AcceptedView[] winner = new AcceptedView[1];
        doAnswer(call -> {
            winner[0] = request(member.token(), first, AcceptedView.class).getBody();
            return call.callRealMethod();
        }).when(stepUpProofs).consume(member.id(), StepUpPurpose.ACCOUNT_DELETION, second);

        ResponseEntity<AcceptedView> loser = request(member.token(), second, AcceptedView.class);

        assertThat(loser.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(winner[0]).isNotNull();
        assertThat(loser.getBody().requestId()).isEqualTo(winner[0].requestId());
        assertThat(loser.getBody().scheduledAt()).isEqualTo(winner[0].scheduledAt());
        assertThat(requestRows(member.id())).isEqualTo(1);
        assertThat(eventCount(member.id(), "ACCOUNT_DELETION_REQUESTED")).isEqualTo(1);
    }

    @Test
    void repeatAfterADayKeepsTheDeadline() {
        Member member = newMember("repeat");
        String proof = proofFor(member);
        AcceptedView first = request(member.token(), proof, AcceptedView.class).getBody();
        clock.advance(Duration.ofDays(1));
        String token = loginAs(member.email());
        clearInvocations(stepUpProofs);

        ResponseEntity<AcceptedView> again = request(token, proof, AcceptedView.class);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(again.getBody().requestId()).isEqualTo(first.requestId());
        assertThat(again.getBody().scheduledAt()).isEqualTo(first.scheduledAt());
        assertThat(again.getBody().requestedAt()).isEqualTo(first.requestedAt());
        assertThat(requestRows(member.id())).isEqualTo(1);
        assertThat(eventCount(member.id(), "ACCOUNT_DELETION_REQUESTED")).isEqualTo(1);
        verifyNoInteractions(stepUpProofs);
    }

    @Test
    void runStartedAfterThePreviewBlocks() {
        Member member = newMember("late-run");
        assertThat(get(BASE + "/eligibility", member.token(), EligibilityView.class).getBody().eligible()).isTrue();
        insertRun(member.id(), "running");
        String proof = proofFor(member);

        ResponseEntity<ProblemView> response = request(member.token(), proof, ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("account.deletion.blocked");
        assertThat(response.getBody().reasons()).extracting(ReasonView::key).containsExactly("workflow.active_run");
        assertThat(requestRows(member.id())).isZero();
        assertThatThrownBy(() -> stepUpProofs.consume(member.id(), StepUpPurpose.ACCOUNT_DELETION, proof))
                .isInstanceOf(StepUpRequiredException.class);
    }

    @Test
    void supportTableUnreadableIs503(CapturedOutput output) {
        Member member = newMember("unreadable");
        doThrow(new DataAccessResourceFailureException("support_request unreadable")).when(openRequestBlocker)
                .blocks(any());

        ResponseEntity<ProblemView> response = get(BASE + "/eligibility", member.token(), ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo("account.deletion.eligibilityUnavailable");
        assertThat(output.getAll().split("account.deletion.eligibility outcome=unavailable key=support.open_request",
                -1)).hasSize(2);
    }

    @Test
    void oldAccessTokenOnAnotherDevice() {
        Member member = newMember("other-device");
        String laptop = member.token();
        String phone = loginAs(member.email());
        request(phone, proofFor(member), AcceptedView.class);
        double blockedBefore = meters.counter("account.deletion.gate.blocked").count();

        ResponseEntity<ProblemView> english = get("/api/v1/workflows", laptop, ProblemView.class);
        ResponseEntity<ProblemView> vietnamese = getInVietnamese("/api/v1/workflows", laptop);

        assertThat(english.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(english.getBody().code()).isEqualTo(PENDING_CODE);
        assertThat(english.getBody().detail()).isEqualTo(ENGLISH_PENDING);
        assertThat(vietnamese.getBody().code()).isEqualTo(PENDING_CODE);
        assertThat(vietnamese.getBody().detail()).isEqualTo(VIETNAMESE_PENDING);
        assertThat(meters.counter("account.deletion.gate.blocked").count()).isEqualTo(blockedBefore + 2);
    }

    @Test
    void exemptRoutesStayReachable() {
        Member member = newMember("exempt");
        request(member.token(), proofFor(member), AcceptedView.class);
        String token = loginAs(member.email());

        assertThat(get("/api/v1/auth/me", token, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(BASE, token, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(BASE + "/eligibility", token, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(BASE, token, new ProofBody("spent-proof"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(get("/api/v1/features", token, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertNotPending(post("/api/v1/client-errors", token, Map.of(), String.class));
        assertNotPending(post("/api/v1/auth/refresh", token, null, String.class));
        assertNotPending(post("/api/v1/auth/forgot-password", token, Map.of("email", member.email()), String.class));
        assertThat(post("/api/v1/me/step-up", token, Map.of("password", PASSWORD, "purpose", "PAT_CREATE"),
                ProblemView.class).getBody().code()).isEqualTo(PENDING_CODE);
        assertThat(put("/api/v1/auth/profile", token, Map.of("name", "Renamed"), ProblemView.class).getBody().code())
                .isEqualTo(PENDING_CODE);
        assertThat(post("/api/v1/auth/logout", token, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void refreshWhilePendingWithABearerToken() {
        Member member = newMember("refresh-bearer");
        request(member.token(), proofFor(member), AcceptedView.class);
        ResponseEntity<String> signedIn = loginRaw(member.email(), PASSWORD);
        String cookie = refreshCookieOf(signedIn);
        Matcher accessToken = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"").matcher(signedIn.getBody());
        assertThat(accessToken.find()).isTrue();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken.group(1));
        headers.set(HttpHeaders.COOKIE, REFRESH_COOKIE + "=" + cookie);
        ResponseEntity<String> refreshed = restTemplate.exchange("/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void stepUpIsRefusedWhilePending() {
        Member member = newMember("step-up-pending");
        request(member.token(), proofFor(member), AcceptedView.class);

        ResponseEntity<ProblemView> response = post("/api/v1/me/step-up", member.token(),
                Map.of("password", PASSWORD, "purpose", "ACCOUNT_DELETION"), ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().code()).isEqualTo(PENDING_CODE);
    }

    @Test
    void signInWhilePending() {
        Member member = newMember("sign-in-pending");
        request(member.token(), proofFor(member), AcceptedView.class);

        String token = loginAs(member.email());

        ResponseEntity<ProblemView> response = get("/api/v1/workflows", token, ProblemView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().code()).isEqualTo(PENDING_CODE);
    }

    @Test
    void cancelledAccountIsServedAgain() {
        Member member = newMember("served-again");
        request(member.token(), proofFor(member), AcceptedView.class);
        assertThat(get("/api/v1/workflows", member.token(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(post(BASE + "/cancel", member.token(), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(get("/api/v1/workflows", member.token(), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void chatRoutesAreClosedToo() {
        Member member = newMember("chat-closed");
        request(member.token(), proofFor(member), AcceptedView.class);

        ResponseEntity<ProblemView> response = get("/api/v1/chat/conversations", member.token(), ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().code()).isEqualTo(PENDING_CODE);
    }

    @Test
    void noRequestStatus() {
        Member member = newMember("no-request");

        StatusView status = get(BASE, member.token(), StatusView.class).getBody();
        MeBody me = get("/api/v1/auth/me", member.token(), MeBody.class).getBody();

        assertThat(status).isEqualTo(new StatusView(true, 30, "NONE", null, null));
        assertThat(me.accountDeletion()).isNull();
    }

    @Test
    void pendingRequestInMe() {
        Member member = newMember("pending-me");
        AcceptedView accepted = request(member.token(), proofFor(member), AcceptedView.class).getBody();
        String token = loginAs(member.email());

        MeBody me = get("/api/v1/auth/me", token, MeBody.class).getBody();
        StatusView status = get(BASE, token, StatusView.class).getBody();

        assertThat(me.accountDeletion()).isEqualTo(
                new DeletionView("PENDING", accepted.requestedAt(), accepted.scheduledAt()));
        assertThat(status.state()).isEqualTo("PENDING");
        assertThat(status.scheduledAt()).isEqualTo(accepted.scheduledAt());
    }

    @Test
    void cancelInTime() {
        Member member = newMember("cancel");
        String session = refreshCookieOf(loginRaw(member.email(), PASSWORD));
        request(member.token(), proofFor(member), AcceptedView.class);
        String token = loginAs(member.email());

        ResponseEntity<CancelledView> response = post(BASE + "/cancel", token, null, CancelledView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().state()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT state FROM account_deletion_request WHERE user_id = ?", String.class,
                member.id())).isEqualTo("cancelled");
        assertThat(projectionSet(member.id())).isFalse();
        assertThat(get("/api/v1/auth/me", token, MeBody.class).getBody().accountDeletion()).isNull();
        assertThat(eventCount(member.id(), "ACCOUNT_DELETION_CANCELLED")).isEqualTo(1);
        assertThat(refresh(session).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<AcceptedView> again = request(token, proofFor(member), AcceptedView.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(requestRows(member.id())).isEqualTo(2);
    }

    @Test
    void cancelAfterTheDeadline() {
        Member member = newMember("late-cancel");
        AcceptedView accepted = request(member.token(), proofFor(member), AcceptedView.class).getBody();
        clock.advance(Duration.ofDays(30).plusMinutes(1));
        String token = loginAs(member.email());

        ResponseEntity<ProblemView> response = post(BASE + "/cancel", token, null, ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().code()).isEqualTo("account.deletion.recoveryExpired");
        assertThat(jdbc.queryForObject("SELECT state FROM account_deletion_request WHERE id = ?", String.class,
                accepted.requestId())).isEqualTo("pending");
        assertThat(get(BASE, token, StatusView.class).getBody().state()).isEqualTo("EXPIRED");
        assertThat(get("/api/v1/auth/me", token, MeBody.class).getBody().accountDeletion().state())
                .isEqualTo("EXPIRED");
        ResponseEntity<ProblemView> walled = get("/api/v1/workflows", token, ProblemView.class);
        assertThat(walled.getBody().code()).isEqualTo(PENDING_CODE);
    }

    @Test
    void twoCancelsAtOnce() throws Exception {
        Member member = newMember("two-cancels");
        request(member.token(), proofFor(member), AcceptedView.class);

        List<ResponseEntity<String>> answers = runTogether(
                () -> post(BASE + "/cancel", member.token(), null, String.class),
                () -> post(BASE + "/cancel", member.token(), null, String.class));

        assertThat(answers).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        assertThat(answers.stream().filter(a -> a.getStatusCode() == HttpStatus.CONFLICT).findFirst().get().getBody())
                .contains("account.deletion.notPending");
        assertThat(eventCount(member.id(), "ACCOUNT_DELETION_CANCELLED")).isEqualTo(1);
    }

    @Test
    void nothingToCancel() {
        Member member = newMember("nothing");

        ResponseEntity<ProblemView> response = post(BASE + "/cancel", member.token(), null, ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("account.deletion.notPending");
    }

    @Nested
    @TestPropertySource(properties = {
            "flowcortex.phone-gate.enabled=true",
            "flowcortex.phone-verification.enabled=true",
            "flowcortex.sms.sender=outbox"})
    class WithThePhoneGateOn {

        @Test
        void aPendingPhoneHeldMemberCanCancel() {
            Member member = newMember("phone-held");
            UUID requestId = UUID.randomUUID();
            Instant now = clock.instant();
            jdbc.update("INSERT INTO account_deletion_request (id, user_id, state, requested_at, scheduled_at, "
                    + "created_at) VALUES (?, ?, 'pending', ?, ?, ?)", requestId, member.id(), Timestamp.from(now),
                    Timestamp.from(now.plus(Duration.ofDays(30))), Timestamp.from(now));
            jdbc.update("UPDATE app_user SET deletion_requested_at = ?, deletion_scheduled_at = ? WHERE id = ?",
                    Timestamp.from(now), Timestamp.from(now.plus(Duration.ofDays(30))), member.id());

            ResponseEntity<StatusView> status = get(BASE, member.token(), StatusView.class);
            ResponseEntity<ProblemView> held = get("/api/v1/workflows", member.token(), ProblemView.class);
            ResponseEntity<CancelledView> cancelled = post(BASE + "/cancel", member.token(), null,
                    CancelledView.class);

            assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(status.getBody().state()).isEqualTo("PENDING");
            assertThat(held.getBody().code()).isEqualTo(PENDING_CODE);
            assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(cancelled.getBody().state()).isEqualTo("CANCELLED");
        }
    }

    private Member newMember(String prefix) {
        UserView admin = userService.createInitialAdmin(uniqueEmail(prefix + "-admin"), PASSWORD, "Admin");
        UserView user = userService.createUser(admin.id(), uniqueEmail(prefix), PASSWORD, "Member", "MEMBER");
        return new Member(user.id(), user.email(), loginAs(user.email()));
    }

    private String loginAs(String email) {
        String token = login(email, PASSWORD);
        assertThat(token).as("login of %s", email).isNotNull();
        return token;
    }

    private ResponseEntity<StepUpView> stepUp(Member member, String purpose) {
        ResponseEntity<StepUpView> response = post("/api/v1/me/step-up", member.token(),
                Map.of("password", PASSWORD, "purpose", purpose), StepUpView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private String proofFor(Member member) {
        return stepUp(member, "ACCOUNT_DELETION").getBody().stepUpProof();
    }

    private <T> ResponseEntity<T> request(String token, String proof, Class<T> type) {
        return post(BASE, token, new ProofBody(proof), type);
    }

    private ResponseEntity<ProblemView> getInVietnamese(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "vi");
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), ProblemView.class);
    }

    private ResponseEntity<String> refresh(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, REFRESH_COOKIE + "=" + cookie);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/api/v1/auth/refresh", HttpMethod.POST, new HttpEntity<>(headers),
                String.class);
    }

    private static String refreshCookieOf(ResponseEntity<?> response) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).as("Set-Cookie").isNotNull();
        String header = cookies.stream().filter(c -> c.startsWith(REFRESH_COOKIE + "=")).findFirst()
                .orElseThrow(() -> new AssertionError("no refresh_token cookie in " + cookies));
        return header.substring((REFRESH_COOKIE + "=").length(), header.indexOf(';'));
    }

    private long requestRows(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM account_deletion_request WHERE user_id = ?", Long.class,
                userId);
    }

    private boolean projectionSet(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT deletion_requested_at IS NOT NULL OR deletion_scheduled_at IS NOT NULL FROM app_user "
                        + "WHERE id = ?", Boolean.class, userId));
    }

    private long eventCount(UUID userId, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM auth_event WHERE user_id = ? AND type = ?", Long.class,
                userId, type);
    }

    private void forgetSetting(String key) {
        jdbc.update("DELETE FROM app_setting WHERE key = ?", key);
    }

    private void insertRun(UUID owner, String status) {
        UUID workflow = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at) "
                + "VALUES (?, ?, 'Deletion fixture', false, ?, ?)", workflow, owner, now, now);
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, finished_at) "
                + "VALUES (?, ?, ?, ?, 'seed', ?, NULL)", UUID.randomUUID(), workflow, owner, status, now);
    }

    private static void assertNotPending(ResponseEntity<String> response) {
        assertThat(response.getBody() == null ? "" : response.getBody()).doesNotContain(PENDING_CODE);
    }

    @SafeVarargs
    private static <T> List<T> runTogether(Callable<T>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        CountDownLatch ready = new CountDownLatch(calls.length);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> running = new ArrayList<>();
            for (Callable<T> call : calls) {
                running.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<T> answers = new ArrayList<>();
            for (Future<T> future : running) {
                answers.add(future.get(30, TimeUnit.SECONDS));
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Clocks {

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }
}
