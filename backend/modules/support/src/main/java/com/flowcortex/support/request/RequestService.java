package com.flowcortex.support.request;

import com.flowcortex.media.api.MediaDownload;
import com.flowcortex.media.api.StoredAttachment;
import com.flowcortex.settings.api.Settings;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.support.config.SupportLifecycleSettings;
import com.flowcortex.support.request.InboxQuery.InboxFilter;
import com.flowcortex.support.request.RequestRepository.AssigneeGroup;
import com.flowcortex.support.request.RequestRepository.AttachmentRow;
import com.flowcortex.support.request.RequestRepository.FacetRows;
import com.flowcortex.support.request.RequestRepository.MessageRow;
import com.flowcortex.support.request.RequestRepository.RequestRow;
import com.flowcortex.support.request.RequestStateMachine.Move;
import com.flowcortex.support.request.RequestStateMachine.Refusal;
import com.flowcortex.support.request.RequestStateMachine.Transition;
import com.flowcortex.support.request.RequestViews.AdminDetail;
import com.flowcortex.support.request.RequestViews.AdminFacets;
import com.flowcortex.support.request.RequestViews.AdminPage;
import com.flowcortex.support.request.RequestViews.AdminSummary;
import com.flowcortex.support.request.RequestViews.AssigneeCount;
import com.flowcortex.support.request.RequestViews.AssigneeFacet;
import com.flowcortex.support.request.RequestViews.Attachment;
import com.flowcortex.support.request.RequestViews.BulkAssignFailure;
import com.flowcortex.support.request.RequestViews.BulkAssignResult;
import com.flowcortex.support.request.RequestViews.ConversationMessage;
import com.flowcortex.support.request.RequestViews.Detail;
import com.flowcortex.support.request.RequestViews.FacetCount;
import com.flowcortex.support.request.RequestViews.Message;
import com.flowcortex.support.request.RequestViews.Requester;
import com.flowcortex.support.request.RequestViews.Summary;
import com.flowcortex.support.request.RequestViews.SupportAssignee;
import com.flowcortex.support.web.SupportProblemException;
import com.flowcortex.user.api.AdminPermissions;
import com.flowcortex.user.api.UserDirectory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Members file and follow their own requests; admins answer and move them. Every status change goes through the
 * lifecycle table and a conditional update, so of two concurrent changes exactly one wins. Attachments are copied
 * before the write's transaction opens; the write, its idempotency claim, its reply-mail due marker and the
 * member's in-app notice commit together.
 */
@Service
class RequestService {

    static final Set<String> TYPES = Set.of("account", "credit", "workflow", "technical", "other");
    static final int PAGE_SIZE = 20;
    static final int MAX_PAGE = 10_000;
    static final int MAX_BULK = 100;
    private static final List<String> TYPE_ORDER = List.of("account", "credit", "workflow", "technical", "other");

    private static final String CREATE = "request";
    private static final String REPLY = "message";

    private static final Logger LOG = LoggerFactory.getLogger(RequestService.class);
    private static final Map<String, RequestEvent> ADMIN_REPLY_TARGETS = Map.of(
            "waiting_member", RequestEvent.ADMIN_REPLY_WAITING_MEMBER, "resolved", RequestEvent.ADMIN_REPLY_RESOLVED);
    private static final Map<String, RequestEvent> ADMIN_STATUS_TARGETS = new LinkedHashMap<>();

    static {
        ADMIN_STATUS_TARGETS.put("in_progress", RequestEvent.ADMIN_SET_IN_PROGRESS);
        ADMIN_STATUS_TARGETS.put("resolved", RequestEvent.ADMIN_SET_RESOLVED);
        ADMIN_STATUS_TARGETS.put("closed", RequestEvent.ADMIN_SET_CLOSED);
    }

    private final RequestRepository requests;
    private final IdempotencyGuard idempotency;
    private final ApplicationEventPublisher events;
    private final AttachmentService attachments;
    private final UserDirectory users;
    private final AdminPermissions adminPermissions;
    private final Settings settings;
    private final TransactionTemplate transaction;
    private final Clock clock;

    RequestService(RequestRepository requests, IdempotencyGuard idempotency, ApplicationEventPublisher events,
            AttachmentService attachments, UserDirectory users, AdminPermissions adminPermissions, Settings settings,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.requests = requests;
        this.idempotency = idempotency;
        this.events = events;
        this.attachments = attachments;
        this.users = users;
        this.adminPermissions = adminPermissions;
        this.settings = settings;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    record NewRequest(String type, String subject, String description, List<UUID> uploadIds, UUID aiConversationId) {
    }

    /** @param locale the member's language, kept on the request so the reply mail goes out in it */
    Detail create(UUID memberId, NewRequest input, Optional<String> key, Locale locale) {
        String type = input.type() == null ? "" : input.type().trim();
        String subject = trimmed(input.subject());
        String description = trimmed(input.description());
        Map<String, LocalizedMessage> errors = new LinkedHashMap<>();
        if (!TYPES.contains(type)) {
            errors.put("type", new LocalizedMessage("support.request.error.type"));
        }
        if (!lengthBetween(subject, 5, 120)) {
            errors.put("subject", new LocalizedMessage("support.request.error.subject"));
        }
        if (!lengthBetween(description, 10, 5000)) {
            errors.put("description", new LocalizedMessage("support.request.error.description"));
        }
        if (input.aiConversationId() != null && !requests.ownsConversation(input.aiConversationId(), memberId)) {
            errors.put("aiConversationId", new LocalizedMessage("support.request.error.conversation"));
        }
        requireValid(errors);
        List<UUID> uploadIds = AttachmentService.checkCount(input.uploadIds());
        Instant now = Instant.now(clock);
        Optional<UUID> earlier = key.flatMap(k -> idempotency.find(memberId, k, CREATE, null, now));
        if (earlier.isPresent()) {
            return memberDetail(requireById(earlier.get()));
        }
        UUID requestId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        List<StoredAttachment> kept = attachments.keep(memberId, requestId, messageId, uploadIds);
        Optional<UUID> winner = writeKeeping(kept, () -> {
            Instant at = Instant.now(clock);
            Optional<UUID> claimed = key.flatMap(k -> idempotency.claim(memberId, k, CREATE, null, requestId, at));
            if (claimed.isPresent()) {
                return claimed;
            }
            long number = requests.insertRequest(requestId, memberId, type, subject, input.aiConversationId(),
                    locale.getLanguage(), at);
            requests.insertMessage(messageId, requestId, "member", memberId, description, at);
            requests.insertAttachments(messageId, kept, at);
            LOG.info("support.request outcome=created request={} type={} attachments={}", number, type, kept.size());
            return Optional.<UUID>empty();
        });
        return memberDetail(requireById(winner.orElse(requestId)));
    }

    List<Summary> list(UUID memberId) {
        return requests.listOfRequester(memberId).stream()
                .map(row -> new Summary(row.number(), row.type(), row.subject(), row.status().value(),
                        row.memberUnread(), row.createdAt(), row.lastActivityAt()))
                .toList();
    }

    /** Opening a request clears its unread mark, and the first opening of a resolution starts its close clock. */
    Detail open(UUID memberId, long number) {
        RequestRow row = owned(memberId, number);
        requests.markSeen(row.id(), Instant.now(clock));
        return memberDetail(requireById(row.id()));
    }

    Detail reply(UUID memberId, long number, String body, List<UUID> uploadIds, Optional<String> key) {
        RequestRow row = owned(memberId, number);
        String text = requireBody(body);
        List<UUID> ids = AttachmentService.checkCount(uploadIds);
        Instant now = Instant.now(clock);
        Optional<UUID> earlier = key.flatMap(k -> idempotency.find(memberId, k, REPLY, row.id(), now));
        if (earlier.isPresent()) {
            return memberDetail(requestOfMessage(earlier.get()));
        }
        Move move = allowed(row, RequestEvent.MEMBER_REPLY);
        UUID messageId = UUID.randomUUID();
        List<StoredAttachment> kept = attachments.keep(memberId, row.id(), messageId, ids);
        Optional<UUID> winner = writeKeeping(kept, () -> {
            Instant at = Instant.now(clock);
            Optional<UUID> claimed = key.flatMap(k -> idempotency.claim(memberId, k, REPLY, row.id(), messageId, at));
            if (claimed.isPresent()) {
                return claimed;
            }
            requireMoved(row, move, null, at);
            requests.insertMessage(messageId, row.id(), "member", memberId, text, at);
            requests.insertAttachments(messageId, kept, at);
            LOG.info("support.request outcome=replied request={} actor=member attachments={}", row.number(),
                    kept.size());
            return Optional.<UUID>empty();
        });
        return memberDetail(winner.map(this::requestOfMessage).orElseGet(() -> requireById(row.id())));
    }

    Detail cancel(UUID memberId, long number) {
        return memberDetail(change(owned(memberId, number), RequestEvent.MEMBER_CANCEL, "cancelled"));
    }

    Detail confirm(UUID memberId, long number) {
        return memberDetail(change(owned(memberId, number), RequestEvent.MEMBER_CONFIRM, "confirmed"));
    }

    long unreadCount(UUID memberId) {
        return requests.countUnread(memberId);
    }

    /** @param callerId what {@code assignee=me} stands for */
    AdminPage page(String status, String type, String assignee, String created, String q, String sort, int page,
            UUID callerId) {
        InboxFilter filter = inboxFilter(status, type, assignee, created, q, sort, page, callerId);
        List<RequestRow> rows = requests.page(filter, page * PAGE_SIZE, PAGE_SIZE);
        Set<UUID> ids = new LinkedHashSet<>();
        rows.forEach(row -> ids.add(row.requesterId()));
        rows.stream().map(RequestRow::assigneeId).filter(Objects::nonNull).forEach(ids::add);
        Map<UUID, String> emails = users.findEmailsByIds(List.copyOf(ids));
        List<AdminSummary> items = rows.stream()
                .map(row -> new AdminSummary(row.number(), row.type(), row.subject(), row.status().value(),
                        requester(row.requesterId(), emails), assignee(row.assigneeId(), emails), row.createdAt(),
                        row.lastActivityAt()))
                .toList();
        return new AdminPage(items, page, PAGE_SIZE, requests.count(filter));
    }

    /** The inbox's counts under the same filters as {@link #page}, each ignoring its own selection. */
    AdminFacets facets(String status, String type, String assignee, String created, String q, UUID callerId) {
        InboxFilter filter = inboxFilter(status, type, assignee, created, q, null, 0, callerId);
        FacetRows rows = requests.facets(filter, callerId);
        Map<UUID, String> emails = users.findEmailsByIds(
                rows.people().stream().map(AssigneeGroup::id).toList());
        List<AssigneeCount> people = rows.people().stream()
                .map(group -> new AssigneeCount(group.id(), emails.get(group.id()), group.count())).toList();
        return new AdminFacets(
                counts(Arrays.stream(RequestStatus.values()).map(RequestStatus::value).toList(), rows.status()),
                counts(TYPE_ORDER, rows.type()),
                counts(Arrays.stream(InboxQuery.CreatedBucket.values()).map(InboxQuery.CreatedBucket::value).toList(),
                        rows.created()),
                new AssigneeFacet(rows.unassigned(), rows.mine(), people));
    }

    /**
     * Sets, changes or (with a null {@code assigneeId}) clears the assignee. Assigning a new request picks it up;
     * the same assignee on a request that would keep its status changes nothing.
     */
    AdminDetail assign(UUID adminId, long number, UUID assigneeId) {
        RequestRow row = byNumber(number);
        if (assigneeId != null) {
            requireEligible(assigneeId);
        }
        Move move = allowed(row, RequestEvent.ADMIN_ASSIGN);
        RequestStatus target = assigneeId == null ? row.status() : move.target();
        if (Objects.equals(row.assigneeId(), assigneeId) && target == row.status()) {
            return adminView(row);
        }
        transaction.executeWithoutResult(status -> {
            Instant at = Instant.now(clock);
            if (!requests.assign(row.id(), row.status(), target, assigneeId, at)) {
                throw refusal(row, "request-state-changed",
                        new LocalizedMessage("support.request.changedMeanwhile", row.number()));
            }
            notifyMember(row, RequestEvent.ADMIN_ASSIGN, target, null, at);
        });
        LOG.info("support.request outcome=assigned request={} actor={} cleared={} picked_up={}", row.number(),
                adminId, assigneeId == null, target != row.status());
        return adminView(requireById(row.id()));
    }

    /**
     * Assigns up to {@link #MAX_BULK} distinct requests to one assignee, each in its own transaction, so a
     * request that fails does not stop the others.
     */
    BulkAssignResult bulkAssign(UUID adminId, List<Long> numbers, UUID assigneeId) {
        requireNumbers(numbers);
        if (assigneeId == null) {
            requireValid(Map.of("assigneeId", new LocalizedMessage("support.request.error.assigneeRequired")));
        }
        requireEligible(assigneeId);
        List<Long> assigned = new ArrayList<>();
        List<Long> unchanged = new ArrayList<>();
        List<BulkAssignFailure> failed = new ArrayList<>();
        for (long number : numbers) {
            BulkAssignOutcome outcome = transaction.execute(status -> assignOne(number, assigneeId));
            switch (outcome) {
                case ASSIGNED -> assigned.add(number);
                case UNCHANGED -> unchanged.add(number);
                default -> failed.add(new BulkAssignFailure(number, outcome.reason()));
            }
        }
        LOG.info("support.request outcome=bulk_assigned actor={} assigned={} unchanged={} failed={}", adminId,
                assigned.size(), unchanged.size(), failed.size());
        return new BulkAssignResult(assigned, unchanged, failed);
    }

    private BulkAssignOutcome assignOne(long number, UUID assigneeId) {
        RequestRow row = requests.findByNumber(number).orElse(null);
        BulkAssignOutcome planned = BulkAssignOutcome.plan(row, assigneeId);
        if (planned != BulkAssignOutcome.TO_WRITE) {
            return planned;
        }
        Move move = allowed(row, RequestEvent.ADMIN_ASSIGN);
        Instant at = Instant.now(clock);
        boolean won = requests.assign(row.id(), row.status(), move.target(), assigneeId, at);
        if (won) {
            notifyMember(row, RequestEvent.ADMIN_ASSIGN, move.target(), null, at);
        }
        return BulkAssignOutcome.written(won);
    }

    private void requireEligible(UUID assigneeId) {
        if (!adminPermissions.holds(assigneeId, Permission.SUPPORT_TICKET_REPLY)) {
            requireValid(Map.of("assigneeId", new LocalizedMessage("support.request.error.assigneeIneligible")));
        }
    }

    private static void requireNumbers(List<Long> numbers) {
        boolean valid = numbers != null && !numbers.isEmpty() && numbers.size() <= MAX_BULK
                && numbers.stream().allMatch(number -> number != null && number > 0)
                && new HashSet<>(numbers).size() == numbers.size();
        if (!valid) {
            requireValid(Map.of("numbers", new LocalizedMessage("support.request.error.numbers", MAX_BULK)));
        }
    }

    private InboxFilter inboxFilter(String status, String type, String assignee, String created, String q,
            String sort, int page, UUID callerId) {
        InboxQuery.Parsed parsed = InboxQuery.parse(status, type, assignee, created, q, sort, page, callerId,
                Instant.now(clock));
        requireValid(parsed.errors());
        return parsed.filter();
    }

    private static List<FacetCount> counts(List<String> values, Map<String, Long> found) {
        return values.stream().map(value -> new FacetCount(value, found.getOrDefault(value, 0L))).toList();
    }

    AdminDetail adminDetail(long number) {
        return adminView(byNumber(number));
    }

    AdminDetail adminReply(UUID adminId, long number, String body, List<UUID> uploadIds, String targetStatus,
            Optional<String> key) {
        RequestRow row = byNumber(number);
        String text = requireBody(body);
        RequestEvent event = targetStatus == null ? null : ADMIN_REPLY_TARGETS.get(targetStatus);
        if (event == null) {
            requireValid(Map.of("status", new LocalizedMessage("support.request.error.replyStatus")));
        }
        List<UUID> ids = AttachmentService.checkCount(uploadIds);
        if (!users.findEmailsByIds(List.of(row.requesterId())).containsKey(row.requesterId())) {
            throw refusal(row, "requester-deleted",
                    new LocalizedMessage("support.request.requesterDeleted", row.number()));
        }
        Instant now = Instant.now(clock);
        Optional<UUID> earlier = key.flatMap(k -> idempotency.find(adminId, k, REPLY, row.id(), now));
        if (earlier.isPresent()) {
            return adminView(requestOfMessage(earlier.get()));
        }
        Move move = allowed(row, event);
        Duration mailWindow = Duration.ofMinutes(settings.get(SupportLifecycleSettings.REPLY_MAIL_WINDOW_MINUTES)
                .effective());
        UUID messageId = UUID.randomUUID();
        List<StoredAttachment> kept = attachments.keep(adminId, row.id(), messageId, ids);
        Optional<UUID> winner = writeKeeping(kept, () -> {
            Instant at = Instant.now(clock);
            Optional<UUID> claimed = key.flatMap(k -> idempotency.claim(adminId, k, REPLY, row.id(), messageId, at));
            if (claimed.isPresent()) {
                return claimed;
            }
            requireMoved(row, move, Boolean.TRUE, at);
            requests.insertMessage(messageId, row.id(), "admin", adminId, text, at);
            requests.insertAttachments(messageId, kept, at);
            requests.markReplyMailDue(row.id(), messageId, at.plus(mailWindow));
            notifyMember(row, event, move.target(), messageId, at);
            LOG.info("support.request outcome=replied request={} actor=admin status={} attachments={}", row.number(),
                    move.target().value(), kept.size());
            return Optional.<UUID>empty();
        });
        return adminView(winner.map(this::requestOfMessage).orElseGet(() -> requireById(row.id())));
    }

    AdminDetail setStatus(long number, String targetStatus) {
        RequestRow row = byNumber(number);
        RequestEvent event = targetStatus == null ? null : ADMIN_STATUS_TARGETS.get(targetStatus);
        if (event == null) {
            requireValid(Map.of("status", new LocalizedMessage("support.request.error.status")));
        }
        return adminView(change(row, event, "status_changed"));
    }

    long openCount() {
        return requests.countOpen();
    }

    /**
     * Closes one resolution its member first saw at or before {@code cutoff}, for the close job; false when
     * someone acted on it first, including a re-resolve that restarted its close clock.
     */
    boolean autoClose(RequestRow row, Instant cutoff) {
        Transition transition = RequestStateMachine.next(row.status(), RequestEvent.AUTO_CLOSE);
        if (!(transition instanceof Move move)) {
            return false;
        }
        boolean moved = Boolean.TRUE.equals(transaction.execute(status -> {
            Instant at = Instant.now(clock);
            boolean closed = requests.closeSeenResolution(row.id(), move.closedReason(), cutoff, at);
            if (closed) {
                notifyMember(row, RequestEvent.AUTO_CLOSE, move.target(), null, at);
            }
            return closed;
        }));
        if (moved) {
            LOG.info("support.request outcome=auto_closed request={}", row.number());
        }
        return moved;
    }

    /**
     * Runs {@code write} in one transaction; it answers an earlier result when its idempotency claim lost. The
     * copies already kept are tagged pending again when they end up unreferenced: the claim lost or the write
     * failed.
     */
    private Optional<UUID> writeKeeping(List<StoredAttachment> kept, Supplier<Optional<UUID>> write) {
        Optional<UUID> winner;
        try {
            winner = transaction.execute(status -> write.get());
        } catch (RuntimeException e) {
            attachments.release(kept);
            throw e;
        }
        if (winner.isPresent()) {
            attachments.release(kept);
        }
        return winner;
    }

    private RequestRow change(RequestRow row, RequestEvent event, String outcome) {
        Move move = allowed(row, event);
        transaction.executeWithoutResult(status -> {
            Instant at = Instant.now(clock);
            requireMoved(row, move, null, at);
            notifyMember(row, event, move.target(), null, at);
        });
        LOG.info("support.request outcome={} request={} from={} to={}", outcome, row.number(), row.status().value(),
                move.target().value());
        return requireById(row.id());
    }

    /**
     * Publishes the member's in-app notice for {@code event} inside the caller's transaction, so it exists exactly
     * when the change committed.
     */
    private void notifyMember(RequestRow row, RequestEvent event, RequestStatus to, UUID messageId, Instant at) {
        MemberNotices.plan(event, row.status(), to).ifPresent(notice -> events.publishEvent(MemberNotices.event(
                notice, MemberNotices.idFor(row.id(), messageId, to, at), row.requesterId(), row.number())));
    }

    private Move allowed(RequestRow row, RequestEvent event) {
        Transition transition = RequestStateMachine.next(row.status(), event);
        if (transition instanceof Refusal refusal) {
            throw refusal(row, refusal.code(), new LocalizedMessage("support.request.cannotTransition",
                    row.number(), row.status().value(), event));
        }
        return (Move) transition;
    }

    /** The status read earlier may have changed since: then this change lost and the caller must reload. */
    private void requireMoved(RequestRow row, Move move, Boolean memberUnread, Instant now) {
        if (!requests.move(row.id(), row.status(), move.target(), move.closedReason(), memberUnread, now)) {
            throw refusal(row, "request-state-changed",
                    new LocalizedMessage("support.request.changedMeanwhile", row.number()));
        }
    }

    private static SupportProblemException refusal(RequestRow row, String code,
            LocalizedMessage detail) {
        LOG.info("support.request outcome=refused request={} code={}", row.number(), code);
        return new SupportProblemException(HttpStatus.CONFLICT, code, detail);
    }

    private RequestRow owned(UUID memberId, long number) {
        return requests.findByNumber(number)
                .filter(row -> row.requesterId().equals(memberId))
                .orElseThrow(() -> notFound(number));
    }

    private RequestRow byNumber(long number) {
        return requests.findByNumber(number).orElseThrow(() -> notFound(number));
    }

    private RequestRow requireById(UUID id) {
        return requests.findById(id).orElseThrow(() -> new IllegalStateException("Request vanished: " + id));
    }

    private RequestRow requestOfMessage(UUID messageId) {
        return requireById(requests.findRequestIdOfMessage(messageId)
                .orElseThrow(() -> new IllegalStateException("Message vanished: " + messageId)));
    }

    private static SupportProblemException notFound(long number) {
        return new SupportProblemException(HttpStatus.NOT_FOUND, "request-not-found",
                new LocalizedMessage("support.request.notFound", number));
    }

    private Detail memberDetail(RequestRow row) {
        return new Detail(row.number(), row.type(), row.subject(), row.status().value(), row.closedReason(),
                row.memberUnread(), row.createdAt(), row.lastActivityAt(), row.resolvedAt(), thread(row));
    }

    private AdminDetail adminView(RequestRow row) {
        List<ConversationMessage> conversation = row.aiConversationId() == null ? null
                : requests.conversation(row.aiConversationId()).stream()
                        .map(m -> new ConversationMessage(m.role(), m.text(), m.outcome(), m.createdAt())).toList();
        List<String> allowedStatuses = ADMIN_STATUS_TARGETS.entrySet().stream()
                .filter(target -> RequestStateMachine.next(row.status(), target.getValue()) instanceof Move)
                .map(Map.Entry::getKey).toList();
        Map<UUID, String> emails = users.findEmailsByIds(Stream.of(row.requesterId(), row.assigneeId())
                .filter(Objects::nonNull).distinct().toList());
        return new AdminDetail(row.number(), row.type(), row.subject(), row.status().value(), row.closedReason(),
                row.memberUnread(), row.createdAt(), row.lastActivityAt(), row.resolvedAt(),
                requester(row.requesterId(), emails), assignee(row.assigneeId(), emails), thread(row),
                conversation, row.aiConversationLinked() && row.aiConversationId() == null, allowedStatuses);
    }

    private List<Message> thread(RequestRow row) {
        List<MessageRow> messages = requests.messages(row.id());
        Map<UUID, List<AttachmentRow>> byMessage = requests.attachments(messages.stream().map(MessageRow::id).toList())
                .stream().collect(Collectors.groupingBy(AttachmentRow::messageId));
        List<Message> thread = new ArrayList<>();
        for (MessageRow message : messages) {
            List<Attachment> images = byMessage.getOrDefault(message.id(), List.of()).stream().map(attachment -> {
                MediaDownload download = attachments.download(attachment.bucket(), attachment.objectKey(),
                        row.number(), attachment.position(), attachment.contentType());
                return new Attachment(attachment.position(), attachment.contentType(), attachment.sizeBytes(),
                        download.url(), download.expiresAt());
            }).toList();
            thread.add(new Message(message.id(), message.authorKind(), message.body(), message.createdAt(), images));
        }
        return thread;
    }

    private static Requester requester(UUID id, Map<UUID, String> emails) {
        String email = emails.get(id);
        return new Requester(id, email, email == null);
    }

    private static SupportAssignee assignee(UUID id, Map<UUID, String> emails) {
        if (id == null) {
            return null;
        }
        String email = emails.get(id);
        return new SupportAssignee(id, email, email == null);
    }

    private static String requireBody(String body) {
        String text = trimmed(body);
        if (!lengthBetween(text, 1, 5000)) {
            requireValid(Map.of("body", new LocalizedMessage("support.request.error.body")));
        }
        return text;
    }

    private static void requireValid(Map<String, LocalizedMessage> errors) {
        if (!errors.isEmpty()) {
            throw new SupportProblemException(HttpStatus.BAD_REQUEST, "validation-failed",
                    new LocalizedMessage("problem.validationFailed")).withFieldErrors(errors);
        }
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.strip();
    }

    private static boolean lengthBetween(String value, int min, int max) {
        int length = value.codePointCount(0, value.length());
        return length >= min && length <= max;
    }
}
