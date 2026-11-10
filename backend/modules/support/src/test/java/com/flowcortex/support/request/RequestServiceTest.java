package com.flowcortex.support.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaDownload;
import com.flowcortex.media.api.StoredAttachment;
import com.flowcortex.support.request.RequestRepository.AttachmentRow;
import com.flowcortex.support.request.RequestRepository.ConversationMessageRow;
import com.flowcortex.support.request.RequestRepository.MessageRow;
import com.flowcortex.support.request.RequestRepository.RequestRow;
import com.flowcortex.support.request.RequestService.NewRequest;
import com.flowcortex.support.request.RequestViews.AdminDetail;
import com.flowcortex.support.request.RequestViews.AdminPage;
import com.flowcortex.support.request.RequestViews.BulkAssignFailure;
import com.flowcortex.support.request.RequestViews.BulkAssignResult;
import com.flowcortex.support.request.RequestViews.Detail;
import com.flowcortex.support.web.SupportProblemException;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.user.api.AdminPermissions;
import com.flowcortex.user.api.UserDirectory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

class RequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");

    private final RequestRepository requests = mock(RequestRepository.class);
    private final IdempotencyGuard idempotency = mock(IdempotencyGuard.class);
    private final MailOutboxRepository outbox = mock(MailOutboxRepository.class);
    private final AttachmentService attachments = mock(AttachmentService.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final AdminPermissions adminPermissions = mock(AdminPermissions.class);
    private final RequestService service = new RequestService(requests, idempotency, outbox, attachments, users,
            adminPermissions, mock(PlatformTransactionManager.class), Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID member = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    @Test
    void aNewRequestIsTrimmedStoredWithItsFirstMessageAndShownNew() {
        StoredAttachment image = new StoredAttachment("flowcortex-media", "support/r/m/1", "image/png", 819_200L);
        UUID upload = UUID.randomUUID();
        when(attachments.keep(eq(member), any(), any(), eq(List.of(upload)))).thenReturn(List.of(image));
        when(requests.insertRequest(any(), eq(member), eq("credit"), eq("Không thấy credit"), isNull(), eq("en"),
                eq(NOW))).thenReturn(13L);
        when(requests.findById(any())).thenAnswer(call -> Optional.of(row(call.getArgument(0), 13, RequestStatus.NEW)));

        Detail detail = service.create(member, new NewRequest("credit", "  Không thấy credit ",
                "Tôi đã nạp nhưng chưa thấy credit.", List.of(upload), null), Optional.empty(), Locale.ENGLISH);

        assertThat(detail.number()).isEqualTo(13);
        assertThat(detail.status()).isEqualTo("new");
        verify(requests).insertMessage(any(), any(), eq("member"), eq(member), eq("Tôi đã nạp nhưng chưa thấy credit."),
                eq(NOW));
        verify(requests).insertAttachments(any(), eq(List.of(image)), eq(NOW));
    }

    @Test
    void shortFieldsAnUnknownTypeAndAForeignConversationAreRefusedBeforeAnythingIsCopied() {
        UUID conversation = UUID.randomUUID();
        when(requests.ownsConversation(conversation, member)).thenReturn(false);

        assertThatThrownBy(() -> service.create(member, new NewRequest("billing", "abc", "  ngắn  ", List.of(),
                conversation), Optional.empty(), Locale.ENGLISH))
                .isInstanceOfSatisfying(SupportProblemException.class, e -> {
                    assertThat(e.code()).isEqualTo("validation-failed");
                    assertThat(e.fieldErrors()).containsOnlyKeys("type", "subject", "description",
                            "aiConversationId");
                });
        verifyNoInteractions(attachments);
    }

    @Test
    void aFourthImageIsRefusedAsAnAttachment() {
        List<UUID> four = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        assertProblem(() -> service.create(member, new NewRequest("other", "Bốn ảnh", "Mô tả đủ mười ký tự.", four,
                null), Optional.empty(), Locale.ENGLISH), HttpStatus.BAD_REQUEST, "attachment-rejected");
    }

    @Test
    void aRepeatedKeyAnswersTheFirstRequestWithoutStoringAnother() {
        UUID first = UUID.randomUUID();
        when(idempotency.find(member, "key", "request", null, NOW)).thenReturn(Optional.of(first));
        when(requests.findById(first)).thenReturn(Optional.of(row(first, 7, RequestStatus.NEW)));

        Detail detail = service.create(member, new NewRequest("credit", "Không thấy credit", "Bấm gửi hai lần liền.",
                List.of(), null), Optional.of("key"), Locale.ENGLISH);

        assertThat(detail.number()).isEqualTo(7);
        verify(requests, never()).insertRequest(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aConcurrentRepeatThatLostTheClaimAnswersTheWinnersRequestAndReleasesItsCopies() {
        UUID winner = UUID.randomUUID();
        UUID upload = UUID.randomUUID();
        List<StoredAttachment> kept = List.of(new StoredAttachment("flowcortex-media", "support/r/m/1", "image/png",
                40L));
        when(attachments.keep(eq(member), any(), any(), eq(List.of(upload)))).thenReturn(kept);
        when(idempotency.claim(eq(member), eq("key"), eq("request"), isNull(), any(), eq(NOW)))
                .thenReturn(Optional.of(winner));
        when(requests.findById(winner)).thenReturn(Optional.of(row(winner, 8, RequestStatus.NEW)));

        Detail detail = service.create(member, new NewRequest("credit", "Không thấy credit", "Bấm gửi hai lần liền.",
                List.of(upload), null), Optional.of("key"), Locale.ENGLISH);

        assertThat(detail.number()).isEqualTo(8);
        verify(requests, never()).insertRequest(any(), any(), any(), any(), any(), any(), any());
        verify(attachments).release(kept);
    }

    @Test
    void aCreateWhoseWriteFailsReleasesItsCopiesAndRethrows() {
        UUID upload = UUID.randomUUID();
        List<StoredAttachment> kept = List.of(new StoredAttachment("flowcortex-media", "support/r/m/1", "image/png",
                40L));
        when(attachments.keep(eq(member), any(), any(), eq(List.of(upload)))).thenReturn(kept);
        when(requests.insertRequest(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new QueryTimeoutException("db down"));

        assertThatThrownBy(() -> service.create(member, new NewRequest("credit", "Không thấy credit",
                "Tôi đã nạp nhưng chưa thấy credit.", List.of(upload), null), Optional.empty(), Locale.ENGLISH))
                .isInstanceOf(QueryTimeoutException.class);
        verify(attachments).release(kept);
    }

    @Test
    void aSavedCreateKeepsItsCopies() {
        UUID upload = UUID.randomUUID();
        when(attachments.keep(eq(member), any(), any(), eq(List.of(upload)))).thenReturn(List.of(
                new StoredAttachment("flowcortex-media", "support/r/m/1", "image/png", 40L)));
        when(requests.findById(any())).thenAnswer(call -> Optional.of(row(call.getArgument(0), 13, RequestStatus.NEW)));

        service.create(member, new NewRequest("credit", "Không thấy credit", "Tôi đã nạp nhưng chưa thấy credit.",
                List.of(upload), null), Optional.empty(), Locale.ENGLISH);

        verify(attachments, never()).release(any());
    }

    @Test
    void aReplyThatLostTheRaceReleasesItsCopies() {
        UUID id = UUID.randomUUID();
        UUID upload = UUID.randomUUID();
        List<StoredAttachment> kept = List.of(new StoredAttachment("flowcortex-media", "support/r/m/1", "image/png",
                40L));
        when(requests.findByNumber(12)).thenReturn(Optional.of(row(id, 12, RequestStatus.WAITING_MEMBER)));
        when(attachments.keep(eq(member), eq(id), any(), eq(List.of(upload)))).thenReturn(kept);
        when(requests.move(any(), any(), any(), any(), any(), any())).thenReturn(false);

        assertProblem(() -> service.reply(member, 12, "Đã thử, vẫn lỗi", List.of(upload), Optional.empty()),
                HttpStatus.CONFLICT, "request-state-changed");
        verify(attachments).release(kept);
    }

    @Test
    void anAdminReplyThatLostTheClaimReleasesItsCopies() {
        UUID id = UUID.randomUUID();
        UUID upload = UUID.randomUUID();
        UUID earlierMessage = UUID.randomUUID();
        List<StoredAttachment> kept = List.of(new StoredAttachment("flowcortex-media", "support/r/m/1", "image/png",
                40L));
        when(requests.findByNumber(12)).thenReturn(Optional.of(row(id, 12, RequestStatus.NEW)));
        when(users.findEmailsByIds(List.of(member))).thenReturn(Map.of(member, "m@example.com"));
        when(attachments.keep(eq(admin), eq(id), any(), eq(List.of(upload)))).thenReturn(kept);
        when(idempotency.claim(eq(admin), eq("key"), eq("message"), eq(id), any(), eq(NOW)))
                .thenReturn(Optional.of(earlierMessage));
        when(requests.findRequestIdOfMessage(earlierMessage)).thenReturn(Optional.of(id));
        when(requests.findById(id)).thenReturn(Optional.of(row(id, 12, RequestStatus.RESOLVED)));

        service.adminReply(admin, 12, "Đã xử lý", List.of(upload), "resolved", Optional.of("key"));

        verify(attachments).release(kept);
        verify(outbox, never()).insert(any(), any(), any(), any());
    }

    @Test
    void anotherMembersNumberIsNotFound() {
        UUID id = UUID.randomUUID();
        when(requests.findByNumber(14)).thenReturn(Optional.of(row(id, 14, RequestStatus.NEW, UUID.randomUUID())));

        assertProblem(() -> service.open(member, 14), HttpStatus.NOT_FOUND, "request-not-found");
        assertProblem(() -> service.open(member, 15), HttpStatus.NOT_FOUND, "request-not-found");
    }

    @Test
    void openingMarksTheRequestSeenAndShowsTheThreadWithSignedImages() {
        UUID id = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        when(requests.findByNumber(12)).thenReturn(Optional.of(row(id, 12, RequestStatus.RESOLVED)));
        when(requests.findById(id)).thenReturn(Optional.of(row(id, 12, RequestStatus.RESOLVED)));
        when(requests.messages(id)).thenReturn(List.of(new MessageRow(message, "admin", "Đã xong", NOW)));
        when(requests.attachments(List.of(message))).thenReturn(List.of(
                new AttachmentRow(message, 1, "flowcortex-media", "support/a/b/1", "image/png", 40L)));
        when(attachments.download("flowcortex-media", "support/a/b/1", 12, 1, "image/png"))
                .thenReturn(new MediaDownload("https://store/signed", NOW.plusSeconds(900)));

        Detail detail = service.open(member, 12);

        verify(requests).markSeen(id, NOW);
        assertThat(detail.messages()).singleElement().satisfies(m -> assertThat(m.attachments()).singleElement()
                .satisfies(a -> assertThat(a.url()).isEqualTo("https://store/signed")));
    }

    @Test
    void aMemberReplyToWaitingMemberMovesItToInProgress() {
        UUID id = UUID.randomUUID();
        when(requests.findByNumber(12)).thenReturn(Optional.of(row(id, 12, RequestStatus.WAITING_MEMBER)));
        when(requests.findById(id)).thenReturn(Optional.of(row(id, 12, RequestStatus.IN_PROGRESS)));
        when(requests.move(id, RequestStatus.WAITING_MEMBER, RequestStatus.IN_PROGRESS, null, null, NOW))
                .thenReturn(true);

        Detail detail = service.reply(member, 12, " Đã thử, vẫn lỗi ", List.of(), Optional.empty());

        assertThat(detail.status()).isEqualTo("in_progress");
        verify(requests).insertMessage(any(), eq(id), eq("member"), eq(member), eq("Đã thử, vẫn lỗi"), eq(NOW));
    }

    @Test
    void aReplyToAClosedRequestOrAnEmptyReplyIsRefused() {
        UUID id = UUID.randomUUID();
        when(requests.findByNumber(9)).thenReturn(Optional.of(row(id, 9, RequestStatus.CLOSED)));

        assertProblem(() -> service.reply(member, 9, "Còn nữa", List.of(), Optional.empty()), HttpStatus.CONFLICT,
                "request-closed");
        assertProblem(() -> service.reply(member, 9, "   ", List.of(), Optional.empty()), HttpStatus.BAD_REQUEST,
                "validation-failed");
        verifyNoInteractions(attachments);
    }

    @Test
    void aChangeThatLostTheRaceAnswersStateChanged() {
        UUID id = UUID.randomUUID();
        when(requests.findByNumber(12)).thenReturn(Optional.of(row(id, 12, RequestStatus.IN_PROGRESS)));
        when(requests.move(any(), any(), any(), any(), any(), any())).thenReturn(false);

        assertProblem(() -> service.cancel(member, 12), HttpStatus.CONFLICT, "request-state-changed");
    }

    @Test
    void confirmClosesAResolvedRequestAndCancelFromResolvedIsRefused() {
        UUID id = UUID.randomUUID();
        when(requests.findByNumber(12)).thenReturn(Optional.of(row(id, 12, RequestStatus.RESOLVED)));
        when(requests.findById(id)).thenReturn(Optional.of(row(id, 12, RequestStatus.CLOSED)));
        when(requests.move(id, RequestStatus.RESOLVED, RequestStatus.CLOSED, "confirmed", null, NOW)).thenReturn(true);

        assertProblem(() -> service.cancel(member, 12), HttpStatus.CONFLICT, "transition-not-allowed");
        assertThat(service.confirm(member, 12).status()).isEqualTo("closed");
    }

    @Test
    void anAdminReplyMarksUnreadAndQueuesOneMail() {
        UUID id = UUID.randomUUID();
        when(requests.findByNumber(12)).thenReturn(Optional.of(row(id, 12, RequestStatus.NEW)));
        when(requests.findById(id)).thenReturn(Optional.of(row(id, 12, RequestStatus.RESOLVED)));
        when(users.findEmailsByIds(List.of(member))).thenReturn(Map.of(member, "m@example.com"));
        when(requests.move(id, RequestStatus.NEW, RequestStatus.RESOLVED, null, Boolean.TRUE, NOW)).thenReturn(true);

        AdminDetail detail = service.adminReply(admin, 12, "Đã xử lý", List.of(), "resolved", Optional.empty());

        assertThat(detail.status()).isEqualTo("resolved");
        assertThat(detail.requester().deleted()).isFalse();
        verify(outbox).insert(eq(id), any(), eq(member), eq(NOW));
        verify(requests).insertMessage(any(), eq(id), eq("admin"), eq(admin), eq("Đã xử lý"), eq(NOW));
    }

    @Test
    void anAdminReplyToADeletedRequesterOrWithAnUnknownStatusIsRefused() {
        UUID id = UUID.randomUUID();
        when(requests.findByNumber(5)).thenReturn(Optional.of(row(id, 5, RequestStatus.NEW)));
        when(users.findEmailsByIds(List.of(member))).thenReturn(Map.of());

        assertProblem(() -> service.adminReply(admin, 5, "Xin chào", List.of(), "resolved", Optional.empty()),
                HttpStatus.CONFLICT, "requester-deleted");
        assertProblem(() -> service.adminReply(admin, 5, "Xin chào", List.of(), "closed", Optional.empty()),
                HttpStatus.BAD_REQUEST, "validation-failed");
        verify(outbox, never()).insert(any(), any(), any(), any());
    }

    @Test
    void anAdminReplyWithoutAStatusIsAValidationErrorOnStatus() {
        UUID id = UUID.randomUUID();
        when(requests.findByNumber(5)).thenReturn(Optional.of(row(id, 5, RequestStatus.NEW)));

        assertThatThrownBy(() -> service.adminReply(admin, 5, "Xin chào", List.of(), null, Optional.empty()))
                .isInstanceOfSatisfying(SupportProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("validation-failed");
                    assertThat(e.fieldErrors()).containsOnlyKeys("status");
                });
        verify(outbox, never()).insert(any(), any(), any(), any());
    }

    @Test
    void anAdminSeesTheRequesterTheLinkedConversationAndTheStatusesTheTableAllows() {
        UUID id = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        RequestRow linked = new RequestRow(id, 12, member, "credit", "Không thấy credit", RequestStatus.NEW,
                conversation, true, false, NOW, null, null, null, NOW, null);
        when(requests.findByNumber(12)).thenReturn(Optional.of(linked));
        when(users.findEmailsByIds(List.of(member))).thenReturn(Map.of());
        when(requests.conversation(conversation)).thenReturn(List.of(
                new ConversationMessageRow("user", "Mất credit", null, NOW)));

        AdminDetail detail = service.adminDetail(12);

        assertThat(detail.requester().deleted()).isTrue();
        assertThat(detail.conversation()).singleElement().satisfies(m -> assertThat(m.text()).isEqualTo("Mất credit"));
        assertThat(detail.conversationExpired()).isFalse();
        assertThat(detail.allowedStatuses()).containsExactly("in_progress", "resolved", "closed");
    }

    @Test
    void aLinkedConversationTheRetentionDeletedShowsAsExpiredWhileNoLinkDoesNot() {
        UUID expiredId = UUID.randomUUID();
        when(requests.findByNumber(12)).thenReturn(Optional.of(new RequestRow(expiredId, 12, member, "credit",
                "Không thấy credit", RequestStatus.NEW, null, true, false, NOW, null, null, null, NOW, null)));
        when(requests.findByNumber(13)).thenReturn(Optional.of(row(UUID.randomUUID(), 13, RequestStatus.NEW)));

        AdminDetail expired = service.adminDetail(12);
        AdminDetail unlinked = service.adminDetail(13);

        assertThat(expired.conversation()).isNull();
        assertThat(expired.conversationExpired()).isTrue();
        assertThat(unlinked.conversation()).isNull();
        assertThat(unlinked.conversationExpired()).isFalse();
    }

    @Test
    void anInboxPageBeyondTenThousandIsRefusedInsteadOfOverflowing() {
        assertProblem(() -> service.page(null, null, null, null, null, null, Integer.MAX_VALUE, admin),
                HttpStatus.BAD_REQUEST, "validation-failed");
        assertProblem(() -> service.page(null, null, null, null, null, null, 10_001, admin), HttpStatus.BAD_REQUEST,
                "validation-failed");
        verify(requests, never()).page(any(), anyInt(), anyInt());
    }

    @Test
    void aClosedRequestCannotBeReopenedAndAnUnknownTargetIsRefused() {
        UUID id = UUID.randomUUID();
        when(requests.findByNumber(9)).thenReturn(Optional.of(row(id, 9, RequestStatus.CLOSED)));

        assertProblem(() -> service.setStatus(9, "in_progress"), HttpStatus.CONFLICT, "transition-not-allowed");
        assertProblem(() -> service.setStatus(9, "waiting_member"), HttpStatus.BAD_REQUEST, "validation-failed");
    }

    @Test
    void theInboxPagesByTwentyAndRefusesUnknownFilters() {
        UUID id = UUID.randomUUID();
        UUID linh = UUID.randomUUID();
        InboxQuery.InboxFilter filter = InboxQuery.parse("new", "credit", "me", null, null, null, 1, admin, NOW)
                .filter();
        when(requests.page(filter, 20, 20)).thenReturn(List.of(row(id, 3, RequestStatus.NEW, member, linh)));
        when(requests.count(filter)).thenReturn(21L);
        when(users.findEmailsByIds(List.of(member, linh))).thenReturn(Map.of(member, "m@example.com"));

        AdminPage page = service.page("new", "credit", "me", null, null, null, 1, admin);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.requester().email()).isEqualTo("m@example.com");
            assertThat(item.assignee().id()).isEqualTo(linh);
            assertThat(item.assignee().deleted()).isTrue();
        });
        assertThat(page.total()).isEqualTo(21);
        assertProblem(() -> service.page("open", "billing", null, null, null, null, -1, admin), HttpStatus.BAD_REQUEST,
                "validation-failed");
    }

    @Test
    void bulkAssignClassifiesEachNumber() {
        UUID linh = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        RequestRow fresh = row(UUID.randomUUID(), 30, RequestStatus.NEW);
        RequestRow already = row(UUID.randomUUID(), 31, RequestStatus.IN_PROGRESS, member, linh);
        RequestRow closed = row(UUID.randomUUID(), 9, RequestStatus.CLOSED);
        RequestRow racing = row(UUID.randomUUID(), 32, RequestStatus.IN_PROGRESS, member, other);
        when(adminPermissions.holds(linh, Permission.SUPPORT_TICKET_REPLY)).thenReturn(true);
        when(requests.findByNumber(30)).thenReturn(Optional.of(fresh));
        when(requests.findByNumber(31)).thenReturn(Optional.of(already));
        when(requests.findByNumber(9)).thenReturn(Optional.of(closed));
        when(requests.findByNumber(32)).thenReturn(Optional.of(racing));
        when(requests.findByNumber(999)).thenReturn(Optional.empty());
        when(requests.assign(fresh.id(), RequestStatus.NEW, RequestStatus.IN_PROGRESS, linh, NOW)).thenReturn(true);
        when(requests.assign(racing.id(), RequestStatus.IN_PROGRESS, RequestStatus.IN_PROGRESS, linh, NOW))
                .thenReturn(false);

        BulkAssignResult result = service.bulkAssign(admin, List.of(30L, 31L, 9L, 999L, 32L), linh);

        assertThat(result.assigned()).containsExactly(30L);
        assertThat(result.unchanged()).containsExactly(31L);
        assertThat(result.failed()).extracting(BulkAssignFailure::number, BulkAssignFailure::reason)
                .containsExactly(tuple(9L, "closed"), tuple(999L, "not_found"), tuple(32L, "changed"));
        verify(requests, never()).assign(eq(already.id()), any(), any(), any(), any());
        verify(requests, never()).assign(eq(closed.id()), any(), any(), any(), any());
    }

    @Test
    void bulkAssignRefusesEmptyOversizedOrRepeatedListsBeforeAnyWrite() {
        UUID linh = UUID.randomUUID();
        List<Long> oversized = LongStream.rangeClosed(1, 101).boxed().toList();

        for (List<Long> numbers : Arrays.asList(List.<Long>of(), oversized, List.of(1L, 1L), null,
                Arrays.asList(1L, null), List.of(0L))) {
            assertThatThrownBy(() -> service.bulkAssign(admin, numbers, linh))
                    .isInstanceOfSatisfying(SupportProblemException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.fieldErrors()).containsOnlyKeys("numbers");
                    });
        }
        verifyNoInteractions(adminPermissions);
        verify(requests, never()).assign(any(), any(), any(), any(), any());
    }

    @Test
    void anIneligibleAssigneeFailsTheWholeBatch() {
        UUID editor = UUID.randomUUID();
        when(adminPermissions.holds(editor, Permission.SUPPORT_TICKET_REPLY)).thenReturn(false);

        assertThatThrownBy(() -> service.bulkAssign(admin, List.of(30L, 31L), editor))
                .isInstanceOfSatisfying(SupportProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.fieldErrors()).containsOnlyKeys("assigneeId");
                });
        assertThatThrownBy(() -> service.bulkAssign(admin, List.of(30L), null))
                .isInstanceOfSatisfying(SupportProblemException.class,
                        e -> assertThat(e.fieldErrors()).containsOnlyKeys("assigneeId"));
        verify(requests, never()).assign(any(), any(), any(), any(), any());
        verify(requests, never()).findByNumber(30);
    }

    @Test
    void theCloseJobClosesOnlyAResolutionStillSeenBeforeTheCutoffAndReportsALostRace() {
        RequestRow resolved = row(UUID.randomUUID(), 12, RequestStatus.RESOLVED);
        Instant cutoff = NOW.minusSeconds(7 * 24 * 3600);
        when(requests.closeSeenResolution(resolved.id(), "auto", cutoff, NOW)).thenReturn(true, false);

        assertThat(service.autoClose(resolved, cutoff)).isTrue();
        assertThat(service.autoClose(resolved, cutoff)).isFalse();
        assertThat(service.autoClose(row(UUID.randomUUID(), 13, RequestStatus.IN_PROGRESS), cutoff)).isFalse();
        verify(requests, never()).move(any(), any(), any(), any(), any(), any());
    }

    private RequestRow row(UUID id, long number, RequestStatus status) {
        return row(id, number, status, member);
    }

    private static RequestRow row(UUID id, long number, RequestStatus status, UUID requester) {
        return row(id, number, status, requester, null);
    }

    private static RequestRow row(UUID id, long number, RequestStatus status, UUID requester, UUID assignee) {
        return new RequestRow(id, number, requester, "credit", "Không thấy credit", status, null, false, false, NOW,
                status == RequestStatus.RESOLVED ? NOW : null, null,
                status == RequestStatus.CLOSED ? "cancelled" : null, NOW, assignee);
    }

    private static void assertProblem(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SupportProblemException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }
}
