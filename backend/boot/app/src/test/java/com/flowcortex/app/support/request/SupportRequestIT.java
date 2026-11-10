package com.flowcortex.app.support.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.app.MailParts;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Tag;

/**
 * Support requests over HTTP with the Help Center on and the attachment content check on: filing,
 * attachments, ownership, the lifecycle per actor, the admin inbox, reply mail with its retries,
 * the unread mark, concurrent changes and repeated submits.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.support.enabled=true",
        "flowcortex.media.upload.content-check.enabled=true",
        "flowcortex.support.mail.interval=PT24H",
        "flowcortex.support.retention.interval=PT24H"})
@AutoConfigureTestRestTemplate
@Import(SupportRequestIT.MailAndClock.class)
@ExtendWith(OutputCaptureExtension.class)
class SupportRequestIT extends AbstractPostgresBootTest {

    private static final URI PROBLEMS = URI.create("https://flow-cortex.dev/problems/");
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D,
        'I', 'H', 'D', 'R', 0, 0, 0, 1};

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ScriptedMailSender mail;

    @Autowired
    private MovableClock clock;

    @Autowired
    private com.flowcortex.support.request.SupportMailJob mailJob;

    private UserView admin;
    private String adminToken;

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    record UploadRequest(String fileName, Long size) {
    }

    record UploadGrantView(UUID uploadId, String url, String method, Map<String, String> headers, long maxBytes) {
    }

    record CreateRequest(String type, String subject, String description, List<UUID> uploadIds) {
    }

    record ReplyRequest(String body, List<UUID> uploadIds) {
    }

    record AdminReplyRequest(String body, List<UUID> uploadIds, String status) {
    }

    record StatusRequest(String status) {
    }

    record AttachmentView(int position, String contentType, long sizeBytes, String url) {
    }

    record MessageView(UUID id, String authorKind, String body, Instant createdAt, List<AttachmentView> attachments) {
    }

    record DetailView(long number, String type, String subject, String status, String closedReason,
            boolean memberUnread, Instant lastActivityAt, List<MessageView> messages) {
    }

    record SummaryView(long number, String type, String subject, String status, boolean memberUnread) {
    }

    record CountView(long count) {
    }

    record RequesterView(UUID id, String email, boolean deleted) {
    }

    record LinkedCreateRequest(String type, String subject, String description, List<UUID> uploadIds,
            UUID aiConversationId) {
    }

    record AdminDetailView(long number, String type, String subject, String status, RequesterView requester,
            List<MessageView> messages, List<Map<String, Object>> conversation, boolean conversationExpired) {
    }

    record AdminSummaryView(long number, String type, String status, RequesterView requester) {
    }

    record AdminPageView(List<AdminSummaryView> items, int page, int size, long total) {
    }

    record Problem(int status, URI type, String detail) {
    }

    /** Either a request view (its request type) or a problem (its problem type). */
    record Outcome(String type) {
    }

    @BeforeEach
    void emptyRequests() {
        jdbcTemplate.update("DELETE FROM support_request");
        jdbcTemplate.update("DELETE FROM support_idempotency");
        mail.reset();
        clock.reset();
        admin = userService.createInitialAdmin(uniqueEmail("req-admin"), "password123", "Admin");
        adminToken = login(admin.email());
    }

    @AfterEach
    void resetClock() {
        clock.reset();
        mail.reset();
    }

    @Test
    void aRequestWithAScreenshotStartsNewWithANumberAndKeepsTheImage() {
        UserView member = member("req-file");
        String token = login(member.email());
        UUID upload = uploaded(member, token, "man-hinh.png", PNG, "image/png");

        ResponseEntity<DetailView> response = exchange(HttpMethod.POST, "/api/v1/support/requests", token,
                new CreateRequest("credit", "  Không thấy credit  ", "Tôi đã nạp nhưng ví vẫn chưa cộng credit.",
                        List.of(upload)), DetailView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        DetailView created = response.getBody();
        assertThat(created.number()).isPositive();
        assertThat(created.status()).isEqualTo("new");
        assertThat(created.subject()).isEqualTo("Không thấy credit");
        assertThat(created.messages()).singleElement().satisfies(message -> {
            assertThat(message.authorKind()).isEqualTo("member");
            assertThat(message.attachments()).singleElement().satisfies(attachment -> {
                assertThat(attachment.contentType()).isEqualTo("image/png");
                assertThat(attachment.sizeBytes()).isEqualTo(PNG.length);
                assertThat(attachment.url()).startsWith(publicEndpoint());
            });
        });
        String key = jdbcTemplate.queryForObject("SELECT object_key FROM support_attachment", String.class);
        assertThat(key).matches("support/[0-9a-f-]{36}/[0-9a-f-]{36}/1");
        assertThat(retentionOf(key)).isEqualTo("support-attachment");
        assertThat(exchange(HttpMethod.GET, "/api/v1/support/requests", token, null, SummaryView[].class).getBody())
                .extracting(SummaryView::number).containsExactly(created.number());
    }

    @Test
    void aPdfOrASixMegabyteImageIsRefusedAtTheGrant() {
        String token = login(member("req-grant").email());

        ResponseEntity<Problem> pdf = exchange(HttpMethod.POST, "/api/v1/support/uploads", token,
                new UploadRequest("hoa-don.pdf", 2048L), Problem.class);
        ResponseEntity<Problem> large = exchange(HttpMethod.POST, "/api/v1/support/uploads", token,
                new UploadRequest("anh.jpg", 6L * 1024 * 1024), Problem.class);
        ResponseEntity<UploadGrantView> atCap = exchange(HttpMethod.POST, "/api/v1/support/uploads", token,
                new UploadRequest("anh.webp", 5L * 1024 * 1024), UploadGrantView.class);

        assertProblem(pdf, HttpStatus.BAD_REQUEST, "attachment-rejected");
        assertProblem(large, HttpStatus.BAD_REQUEST, "attachment-rejected");
        assertThat(atCap.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(atCap.getBody().maxBytes()).isEqualTo(5L * 1024 * 1024);
    }

    @Test
    void aRenamedTextFileIsRefusedAndNothingIsStoredOnARequest() {
        UserView member = member("req-renamed");
        String token = login(member.email());
        UUID upload = uploaded(member, token, "anh.png", "ghi chu cuoc hop".getBytes(StandardCharsets.UTF_8),
                "image/png");

        ResponseEntity<Problem> response = exchange(HttpMethod.POST, "/api/v1/support/requests", token,
                new CreateRequest("technical", "Ảnh bị lỗi", "Ảnh chụp màn hình lỗi đính kèm.", List.of(upload)),
                Problem.class);

        assertProblem(response, HttpStatus.BAD_REQUEST, "attachment-rejected");
        assertThat(response.getBody().detail()).isEqualTo("The file is not a valid image");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM support_request", Long.class)).isZero();
    }

    @Test
    void aFourthImageOnOneMessageIsRefused() {
        UserView member = member("req-fourth");
        String token = login(member.email());
        List<UUID> uploads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            uploads.add(uploaded(member, token, "anh-" + i + ".png", PNG, "image/png"));
        }

        ResponseEntity<Problem> response = exchange(HttpMethod.POST, "/api/v1/support/requests", token,
                new CreateRequest("other", "Bốn ảnh", "Mô tả đủ mười ký tự.", uploads), Problem.class);

        assertProblem(response, HttpStatus.BAD_REQUEST, "attachment-rejected");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM support_request", Long.class)).isZero();
    }

    @Test
    void anotherMembersRequestAnswersLikeAnUnknownNumber() {
        String ownerToken = login(member("req-owner").email());
        long number = file(ownerToken, "account").number();
        String otherToken = login(member("req-other").email());

        ResponseEntity<Problem> foreign = exchange(HttpMethod.GET, "/api/v1/support/requests/" + number, otherToken,
                null, Problem.class);
        ResponseEntity<Problem> unknown = exchange(HttpMethod.GET, "/api/v1/support/requests/999999999", otherToken,
                null, Problem.class);

        assertProblem(foreign, HttpStatus.NOT_FOUND, "request-not-found");
        assertProblem(unknown, HttpStatus.NOT_FOUND, "request-not-found");
        assertThat(exchange(HttpMethod.GET, "/api/v1/support/requests", otherToken, null, SummaryView[].class)
                .getBody()).isEmpty();
    }

    @Test
    void aMemberReplyToWaitingMemberMovesItToInProgressAndAClosedRequestRefusesReplies() {
        String token = login(member("req-reply").email());
        long number = file(token, "workflow").number();
        adminReply(number, "Bạn thử chạy lại giúp mình.", "waiting_member");

        DetailView replied = exchange(HttpMethod.POST, "/api/v1/support/requests/" + number + "/messages", token,
                new ReplyRequest("Đã thử, vẫn lỗi", List.of()), DetailView.class).getBody();
        assertThat(replied.status()).isEqualTo("in_progress");
        assertThat(replied.messages()).extracting(MessageView::body).last().isEqualTo("Đã thử, vẫn lỗi");

        exchange(HttpMethod.POST, "/api/v1/support/requests/" + number + "/cancel", token, null, DetailView.class);
        ResponseEntity<Problem> closed = exchange(HttpMethod.POST, "/api/v1/support/requests/" + number + "/messages",
                token, new ReplyRequest("Còn nữa", List.of()), Problem.class);
        assertProblem(closed, HttpStatus.CONFLICT, "request-closed");
    }

    @Test
    void confirmingAResolvedRequestClosesItWhileCancellingItIsRefused() {
        String token = login(member("req-confirm").email());
        long number = file(token, "credit").number();
        adminReply(number, "Đã cộng lại credit.", "resolved");

        ResponseEntity<Problem> cancel = exchange(HttpMethod.POST, "/api/v1/support/requests/" + number + "/cancel",
                token, null, Problem.class);
        DetailView confirmed = exchange(HttpMethod.POST, "/api/v1/support/requests/" + number + "/confirm", token,
                null, DetailView.class).getBody();

        assertProblem(cancel, HttpStatus.CONFLICT, "transition-not-allowed");
        assertThat(confirmed.status()).isEqualTo("closed");
        assertThat(confirmed.closedReason()).isEqualTo("confirmed");
    }

    @Test
    void theAdminInboxFiltersByStatusAndTypeAndPagesByTwenty() {
        String token = login(member("req-inbox").email());
        for (int i = 0; i < 21; i++) {
            file(token, "credit");
        }
        long account = file(token, "account").number();

        AdminPageView first = exchange(HttpMethod.GET, "/api/v1/admin/support/requests?status=new&type=credit",
                adminToken, null, AdminPageView.class).getBody();
        AdminPageView second = exchange(HttpMethod.GET,
                "/api/v1/admin/support/requests?status=new&type=credit&page=1", adminToken, null,
                AdminPageView.class).getBody();
        AdminPageView accounts = exchange(HttpMethod.GET, "/api/v1/admin/support/requests?type=account", adminToken,
                null, AdminPageView.class).getBody();

        assertThat(first.items()).hasSize(20).allSatisfy(item -> assertThat(item.type()).isEqualTo("credit"));
        assertThat(first.total()).isEqualTo(21);
        assertThat(first.size()).isEqualTo(20);
        assertThat(second.items()).hasSize(1);
        assertThat(first.items().getFirst().number()).isGreaterThan(first.items().getLast().number());
        assertThat(accounts.items()).extracting(AdminSummaryView::number).containsExactly(account);
    }

    @Test
    void anAdminReplyThatResolvesShowsToTheMemberAndAClosedRequestCannotBeReopened() {
        String token = login(member("req-resolve").email());
        long number = file(token, "credit").number();

        AdminDetailView resolved = adminReply(number, "Đã xử lý xong.", "resolved");
        DetailView seen = exchange(HttpMethod.GET, "/api/v1/support/requests/" + number, token, null,
                DetailView.class).getBody();
        AdminDetailView closed = exchange(HttpMethod.PUT, "/api/v1/admin/support/requests/" + number + "/status",
                adminToken, new StatusRequest("closed"), AdminDetailView.class).getBody();
        ResponseEntity<Problem> reopen = exchange(HttpMethod.PUT,
                "/api/v1/admin/support/requests/" + number + "/status", adminToken,
                new StatusRequest("in_progress"), Problem.class);

        assertThat(resolved.status()).isEqualTo("resolved");
        assertThat(seen.status()).isEqualTo("resolved");
        assertThat(seen.messages()).extracting(MessageView::authorKind).containsExactly("member", "admin");
        assertThat(closed.status()).isEqualTo("closed");
        assertProblem(reopen, HttpStatus.CONFLICT, "transition-not-allowed");
    }

    @Test
    void aDeletedRequesterIsFlaggedAndTheReplyIsRefusedWhileTheStatusCanStillClose() {
        UserView member = member("req-deleted");
        long number = file(login(member.email()), "account").number();
        userService.deleteUser(admin.id(), member.id());

        AdminDetailView detail = exchange(HttpMethod.GET, "/api/v1/admin/support/requests/" + number, adminToken,
                null, AdminDetailView.class).getBody();
        ResponseEntity<Problem> reply = exchange(HttpMethod.POST,
                "/api/v1/admin/support/requests/" + number + "/messages", adminToken,
                new AdminReplyRequest("Xin chào", List.of(), "resolved"), Problem.class);
        ResponseEntity<AdminDetailView> close = exchange(HttpMethod.PUT,
                "/api/v1/admin/support/requests/" + number + "/status", adminToken, new StatusRequest("closed"),
                AdminDetailView.class);

        assertThat(detail.requester().deleted()).isTrue();
        assertThat(detail.requester().email()).isNull();
        assertProblem(reply, HttpStatus.CONFLICT, "requester-deleted");
        assertThat(close.getBody().status()).isEqualTo("closed");
    }

    @Test
    void anAdminReplyMarksTheRequestUnreadAndItsMailGoesOutOnce() {
        UserView member = member("req-mail");
        String token = login(member.email());
        long number = file(token, "credit").number();

        adminReply(number, "Đã kiểm tra.", "waiting_member");
        mailJob.sendDue();
        mailJob.sendDue();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM support_mail_outbox", Long.class)).isEqualTo(1);
        assertThat(mail.sentTo(member.email())).singleElement()
                .satisfies(body -> assertThat(body).startsWith("Xin chào,")
                        .contains("http://localhost:5173/?help=request-" + number));
        assertThat(jdbcTemplate.queryForObject("SELECT locale FROM support_request WHERE number = ?", String.class,
                number)).isEqualTo("vi");
        assertThat(exchange(HttpMethod.GET, "/api/v1/support/requests/unread-count", token, null, CountView.class)
                .getBody().count()).isEqualTo(1);
    }

    @Test
    void aRequestFiledInEnglishGetsItsReplyMailInEnglish() {
        UserView member = member("req-mail-en");
        HttpHeaders headers = bearer(login(member.email()));
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en");
        long number = restTemplate.exchange("/api/v1/support/requests", HttpMethod.POST, new HttpEntity<>(
                new CreateRequest("credit", "Missing credit", "I topped up but no credit arrived.", List.of()),
                headers), DetailView.class).getBody().number();

        adminReply(number, "Checked.", "waiting_member");
        mailJob.sendDue();

        assertThat(jdbcTemplate.queryForObject("SELECT locale FROM support_request WHERE number = ?", String.class,
                number)).isEqualTo("en");
        assertThat(mail.sentTo(member.email())).singleElement()
                .satisfies(body -> assertThat(body).startsWith("Hello,")
                        .contains("http://localhost:5173/?help=request-" + number));
    }

    @Test
    void aFailingMailIsRetriedAtGrowingDelaysThenGivenUpWithoutLosingTheReplyOrLoggingTheAddress(
            CapturedOutput output) {
        UserView member = member("req-mail-down");
        long number = file(login(member.email()), "technical").number();
        adminReply(number, "Bạn xem lại giúp.", "waiting_member");
        mail.failNext(6);

        mailJob.sendDue();
        assertThat(attempts()).isEqualTo(1);
        for (long minutes : new long[] {1, 5, 15, 60, 240}) {
            int before = attempts();
            clock.advance(Duration.ofMinutes(minutes).minusSeconds(30));
            mailJob.sendDue();
            assertThat(attempts()).as("no attempt before %d minutes", minutes).isEqualTo(before);
            clock.advance(Duration.ofSeconds(31));
            mailJob.sendDue();
            assertThat(attempts()).as("attempt after %d minutes", minutes).isEqualTo(before + 1);
        }
        clock.advance(Duration.ofDays(1));
        mailJob.sendDue();

        assertThat(attempts()).isEqualTo(6);
        assertThat(mail.sentTo(member.email())).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM support_message WHERE author_kind = 'admin'",
                Long.class)).isEqualTo(1);
        assertThat(output.getOut()).contains("support.reply.mail outcome=failed attempt=1 request=" + number)
                .contains("support.reply.mail outcome=gave_up attempt=6 request=" + number)
                .doesNotContain(member.email());
    }

    @Test
    void aMailThatGetsThroughOnTheThirdAttemptIsSentExactlyOnce() {
        UserView member = member("req-mail-back");
        long number = file(login(member.email()), "credit").number();
        adminReply(number, "Đã xử lý.", "resolved");
        mail.failNext(2);

        mailJob.sendDue();
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        mailJob.sendDue();
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        mailJob.sendDue();
        clock.advance(Duration.ofDays(1));
        mailJob.sendDue();

        assertThat(attempts()).isEqualTo(3);
        assertThat(mail.sentTo(member.email())).hasSize(1);
    }

    @Test
    void openingAnUnreadResolutionClearsTheMarkAndStartsTheCloseClockOnce() {
        String token = login(member("req-unread").email());
        long number = file(token, "credit").number();
        adminReply(number, "Đã giải quyết.", "resolved");
        assertThat(unreadCount(token)).isEqualTo(1);

        exchange(HttpMethod.GET, "/api/v1/support/requests/" + number, token, null, DetailView.class);
        Timestamp firstSeen = seenAt(number);
        exchange(HttpMethod.GET, "/api/v1/support/requests/" + number, token, null, DetailView.class);

        assertThat(unreadCount(token)).isZero();
        assertThat(firstSeen).isNotNull();
        assertThat(seenAt(number)).isEqualTo(firstSeen);
    }

    @Test
    void theOpenCountCountsNewAndInProgress() {
        String token = login(member("req-open").email());
        file(token, "credit");
        file(token, "credit");
        long working = file(token, "workflow").number();
        exchange(HttpMethod.PUT, "/api/v1/admin/support/requests/" + working + "/status", adminToken,
                new StatusRequest("in_progress"), AdminDetailView.class);
        adminReply(file(token, "other").number(), "Chờ bạn.", "waiting_member");

        assertThat(exchange(HttpMethod.GET, "/api/v1/admin/support/requests/open-count", adminToken, null,
                CountView.class).getBody().count()).isEqualTo(3);
    }

    @Test
    void aCancelRacingAResolveLetsExactlyOneWin() throws Exception {
        String token = login(member("req-race").email());
        long number = file(token, "credit").number();
        exchange(HttpMethod.PUT, "/api/v1/admin/support/requests/" + number + "/status", adminToken,
                new StatusRequest("in_progress"), AdminDetailView.class);

        AtomicReference<CompletableFuture<ResponseEntity<Outcome>>> cancel = new AtomicReference<>();
        AtomicReference<CompletableFuture<ResponseEntity<Outcome>>> resolve = new AtomicReference<>();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            jdbcTemplate.queryForObject("SELECT id FROM support_request WHERE number = ? FOR UPDATE", UUID.class,
                    number);
            cancel.set(CompletableFuture.supplyAsync(() -> exchange(HttpMethod.POST,
                    "/api/v1/support/requests/" + number + "/cancel", token, null, Outcome.class)));
            resolve.set(CompletableFuture.supplyAsync(() -> exchange(HttpMethod.POST,
                    "/api/v1/admin/support/requests/" + number + "/messages", adminToken,
                    new AdminReplyRequest("Đã xử lý.", List.of(), "resolved"), Outcome.class)));
            awaitBlockedUpdates(2);
            status.setRollbackOnly();
        });
        List<ResponseEntity<Outcome>> outcomes = List.of(cancel.get().get(10, TimeUnit.SECONDS),
                resolve.get().get(10, TimeUnit.SECONDS));

        assertThat(outcomes).filteredOn(r -> r.getStatusCode().is2xxSuccessful()).hasSize(1);
        assertThat(outcomes).filteredOn(r -> r.getStatusCode() == HttpStatus.CONFLICT).singleElement()
                .satisfies(r -> assertThat(r.getBody().type())
                        .isEqualTo(PROBLEMS.resolve("request-state-changed").toString()));
        String status = jdbcTemplate.queryForObject("SELECT status FROM support_request WHERE number = ?",
                String.class, number);
        assertThat(status).isIn("closed", "resolved");
        long adminMessages = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM support_message WHERE author_kind = 'admin'", Long.class);
        assertThat(adminMessages).isEqualTo("resolved".equals(status) ? 1 : 0);
    }

    @Test
    void theSameIdempotencyKeyTwiceStoresOneRequestAndAnswersTheSameNumber() {
        String token = login(member("req-twice").email());
        String key = UUID.randomUUID().toString();
        CreateRequest body = new CreateRequest("credit", "Không thấy credit", "Bấm gửi hai lần liền.", List.of());

        DetailView first = idempotent(token, key, body);
        DetailView second = idempotent(token, key, body);

        assertThat(second.number()).isEqualTo(first.number());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM support_request", Long.class)).isEqualTo(1);
    }

    @Test
    void twoConcurrentCreatesWithOneKeyStoreOneRequestAndAnswerTheSameNumber() throws Exception {
        UserView member = member("req-twice-concurrent");
        String token = login(member.email());
        String key = UUID.randomUUID().toString();
        CreateRequest body = new CreateRequest("credit", "Không thấy credit", "Bấm gửi hai lần liền.", List.of());

        AtomicReference<CompletableFuture<DetailView>> first = new AtomicReference<>();
        AtomicReference<CompletableFuture<DetailView>> second = new AtomicReference<>();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            // An uncommitted claim on the key: both creates queue behind it, then race each other for it.
            jdbcTemplate.update("INSERT INTO support_idempotency (requester_id, idem_key, kind, result_id, created_at) "
                    + "VALUES (?, ?, 'request', ?, now())", member.id(), key, UUID.randomUUID());
            first.set(CompletableFuture.supplyAsync(() -> idempotent(token, key, body)));
            second.set(CompletableFuture.supplyAsync(() -> idempotent(token, key, body)));
            awaitBlocked("INSERT INTO support_idempotency%", 2);
            status.setRollbackOnly();
        });

        long a = first.get().get(10, TimeUnit.SECONDS).number();
        long b = second.get().get(10, TimeUnit.SECONDS).number();
        assertThat(a).isEqualTo(b);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM support_request", Long.class)).isEqualTo(1);
    }

    @Test
    void aCreateKeyReusedOnAReplyOrAReplyKeyReusedOnAnotherRequestIsRefused() {
        String token = login(member("req-key-reuse").email());
        String createKey = UUID.randomUUID().toString();
        String replyKey = UUID.randomUUID().toString();
        long first = idempotent(token, createKey,
                new CreateRequest("credit", "Không thấy credit", "Bấm gửi hai lần liền.", List.of())).number();
        long second = file(token, "technical").number();
        assertThat(replyWithKey(token, first, replyKey).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Outcome> createKeyOnReply = replyWithKey(token, first, createKey);
        ResponseEntity<Outcome> replyKeyElsewhere = replyWithKey(token, second, replyKey);

        for (ResponseEntity<Outcome> reused : List.of(createKeyOnReply, replyKeyElsewhere)) {
            assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(reused.getBody().type()).isEqualTo(PROBLEMS.resolve("idempotency-key-reused").toString());
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM support_message", Long.class)).isEqualTo(3);
    }

    @Test
    void anExpiredConversationIsToldApartFromNoConversation() {
        UserView member = member("req-conversation");
        String token = login(member.email());
        UUID conversation = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO support_ai_conversation (id, owner_user_id, last_message_at, created_at, "
                + "updated_at) VALUES (?, ?, now(), now(), now())", conversation, member.id());
        ResponseEntity<DetailView> linked = exchange(HttpMethod.POST, "/api/v1/support/requests", token,
                new LinkedCreateRequest("credit", "Không thấy credit", "Trợ lý không trả lời được câu này.",
                        List.of(), conversation), DetailView.class);
        assertThat(linked.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long unlinked = file(token, "credit").number();
        // What the 90-day retention does: the request's link is set null by the foreign key.
        jdbcTemplate.update("DELETE FROM support_ai_conversation WHERE id = ?", conversation);

        AdminDetailView expired = exchange(HttpMethod.GET, "/api/v1/admin/support/requests/"
                + linked.getBody().number(), adminToken, null, AdminDetailView.class).getBody();
        AdminDetailView never = exchange(HttpMethod.GET, "/api/v1/admin/support/requests/" + unlinked, adminToken,
                null, AdminDetailView.class).getBody();

        assertThat(expired.conversation()).isNull();
        assertThat(expired.conversationExpired()).isTrue();
        assertThat(never.conversation()).isNull();
        assertThat(never.conversationExpired()).isFalse();
    }

    @Test
    void aSuspendedMemberIsRefusedOnEveryMemberRoute() {
        UserView member = member("req-suspended");
        String token = login(member.email());
        long number = file(token, "account").number();
        userService.updateUser(admin.id(), member.id(), member.email(), member.name(), "MEMBER", "SUSPENDED");
        String path = "/api/v1/support/requests/" + number;

        assertThat(exchange(HttpMethod.GET, "/api/v1/support/requests", token, null, Problem.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.POST, "/api/v1/support/requests", token,
                new CreateRequest("account", "Bị khoá", "Tài khoản bị khoá rồi.", List.of()), Problem.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.GET, "/api/v1/support/requests/unread-count", token, null, Problem.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.POST, "/api/v1/support/uploads", token, new UploadRequest("a.png", 10L),
                Problem.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.GET, path, token, null, Problem.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.POST, path + "/messages", token, new ReplyRequest("Còn nữa", List.of()),
                Problem.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.POST, path + "/cancel", token, null, Problem.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.POST, path + "/confirm", token, null, Problem.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM support_request WHERE number = ?", String.class,
                number)).isEqualTo("new");
    }

    @Test
    void aReplyLogsItsOutcomeAndNumberButNoAddress(CapturedOutput output) {
        UserView member = member("req-log");
        String token = login(member.email());
        long number = file(token, "credit").number();

        exchange(HttpMethod.POST, "/api/v1/support/requests/" + number + "/messages", token,
                new ReplyRequest("Bổ sung thông tin", List.of()), DetailView.class);

        assertThat(output.getOut()).contains("support.request outcome=created request=" + number)
                .contains("support.request outcome=replied request=" + number)
                .doesNotContain(member.email())
                .doesNotContain("Bổ sung thông tin");
    }

    private DetailView file(String token, String type) {
        ResponseEntity<DetailView> response = exchange(HttpMethod.POST, "/api/v1/support/requests", token,
                new CreateRequest(type, "Cần hỗ trợ", "Mô tả chi tiết vấn đề gặp phải.", List.of()), DetailView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private DetailView idempotent(String token, String key, CreateRequest body) {
        HttpHeaders headers = bearer(token);
        headers.set("Idempotency-Key", key);
        ResponseEntity<DetailView> response = restTemplate.exchange("/api/v1/support/requests", HttpMethod.POST,
                new HttpEntity<>(body, headers), DetailView.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return response.getBody();
    }

    private ResponseEntity<Outcome> replyWithKey(String token, long number, String key) {
        HttpHeaders headers = bearer(token);
        headers.set("Idempotency-Key", key);
        return restTemplate.exchange("/api/v1/support/requests/" + number + "/messages", HttpMethod.POST,
                new HttpEntity<>(new ReplyRequest("Bổ sung thông tin", List.of()), headers), Outcome.class);
    }

    private AdminDetailView adminReply(long number, String body, String status) {
        ResponseEntity<AdminDetailView> response = exchange(HttpMethod.POST,
                "/api/v1/admin/support/requests/" + number + "/messages", adminToken,
                new AdminReplyRequest(body, List.of(), status), AdminDetailView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private UUID uploaded(UserView user, String token, String fileName, byte[] body, String contentType) {
        ResponseEntity<UploadGrantView> grant = exchange(HttpMethod.POST, "/api/v1/support/uploads", token,
                new UploadRequest(fileName, (long) body.length), UploadGrantView.class);
        assertThat(grant.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID uploadId = grant.getBody().uploadId();
        try (S3Client store = objectStore()) {
            store.putObject(o -> o.bucket(UPLOAD_BUCKET).key(user.id() + "/" + uploadId).contentType(contentType),
                    RequestBody.fromBytes(body));
        }
        return uploadId;
    }

    private String retentionOf(String key) {
        try (S3Client store = objectStore()) {
            return store.getObjectTagging(t -> t.bucket(MEDIA_BUCKET).key(key)).tagSet().stream()
                    .filter(tag -> tag.key().equals("flowcortex-retention")).map(Tag::value).findFirst().orElse(null);
        }
    }

    private int attempts() {
        return jdbcTemplate.queryForObject("SELECT attempts FROM support_mail_outbox", Integer.class);
    }

    private Timestamp seenAt(long number) {
        return jdbcTemplate.queryForObject("SELECT resolved_seen_at FROM support_request WHERE number = ?",
                Timestamp.class, number);
    }

    private long unreadCount(String token) {
        return exchange(HttpMethod.GET, "/api/v1/support/requests/unread-count", token, null, CountView.class)
                .getBody().count();
    }

    /** Waits until {@code count} sessions are blocked on the request row the test holds locked. */
    private void awaitBlockedUpdates(int count) {
        awaitBlocked("UPDATE support_request%", count);
    }

    /** Waits until {@code count} sessions running a statement like {@code query} wait on a lock the test holds. */
    private void awaitBlocked(String query, int count) {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            // Inside a transaction pg_stat_activity is a snapshot taken at first read.
            jdbcTemplate.queryForList("SELECT pg_stat_clear_snapshot()");
            Long blocked = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE wait_event_type = 'Lock' AND query LIKE ?", Long.class, query);
            if (blocked != null && blocked >= count) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("Never " + count + " sessions blocked on " + query + ": " + jdbcTemplate.queryForList(
                "SELECT state, wait_event_type, left(query, 60) AS query FROM pg_stat_activity "
                        + "WHERE datname = current_database()"));
    }

    private UserView member(String prefix) {
        return userService.createUser(admin.id(), uniqueEmail(prefix), "password123", "Member", "MEMBER");
    }

    private String login(String email) {
        TokenResponse token = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "password123"), TokenResponse.class).getBody();
        assertThat(token).isNotNull();
        return token.accessToken();
    }

    private <T> ResponseEntity<T> exchange(HttpMethod method, String path, String token, Object body,
            Class<T> responseType) {
        return restTemplate.exchange(path, method, new HttpEntity<>(body, bearer(token)), responseType);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private static void assertProblem(ResponseEntity<Problem> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve(code));
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    /** Captures every mail instead of sending it, after refusing the next N with a send failure. */
    static class ScriptedMailSender extends JavaMailSenderImpl {

        private final List<MimeMessage> sent = new CopyOnWriteArrayList<>();
        private final AtomicInteger failures = new AtomicInteger();

        void failNext(int count) {
            failures.set(count);
        }

        void reset() {
            sent.clear();
            failures.set(0);
        }

        List<String> sentTo(String address) {
            List<String> bodies = new ArrayList<>();
            for (MimeMessage message : sent) {
                try {
                    if (message.getAllRecipients()[0].toString().equals(address)) {
                        bodies.add(MailParts.textOf(message));
                    }
                } catch (MessagingException | IOException e) {
                    throw new IllegalStateException("Could not read a captured message", e);
                }
            }
            return bodies;
        }

        @Override
        protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) throws MailException {
            if (failures.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                throw new MailSendException("Connection refused");
            }
            sent.addAll(List.of(mimeMessages));
        }
    }

    /** System time plus an offset a test can move; every reader of the app's Clock sees it. */
    static class MovableClock extends Clock {

        private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

        void advance(Duration by) {
            offset.updateAndGet(current -> current.plus(by));
        }

        void reset() {
            offset.set(Duration.ZERO);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("fixed to UTC");
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(offset.get());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MailAndClock {

        @Bean
        ScriptedMailSender scriptedMailSender() {
            return new ScriptedMailSender();
        }

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }
}
