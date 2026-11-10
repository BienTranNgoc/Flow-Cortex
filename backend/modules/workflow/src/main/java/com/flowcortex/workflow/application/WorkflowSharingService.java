package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.api.UserDirectory;
import com.flowcortex.workflow.api.AccessLogEntryView;
import com.flowcortex.workflow.api.LinkGrantView;
import com.flowcortex.workflow.api.ShareSettingsView;
import com.flowcortex.workflow.api.SharePage;
import com.flowcortex.workflow.api.SharedWorkflowView;
import com.flowcortex.workflow.api.VisibilityAuditEntryView;
import com.flowcortex.workflow.api.WorkflowVisibility;
import com.flowcortex.workflow.domain.VisibilityChangeTrigger;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowAccessLogEntry;
import com.flowcortex.workflow.domain.WorkflowLinkGrant;
import com.flowcortex.workflow.domain.WorkflowStatus;
import com.flowcortex.workflow.domain.WorkflowVisibilityAuditEntry;
import com.flowcortex.workflow.infrastructure.WorkflowAccessLogRepository;
import com.flowcortex.workflow.infrastructure.WorkflowLinkGrantRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowVisibilityAuditRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who can reach a workflow beyond its owner: the visibility tier and the share key that follows it, key
 * admission, and the owner's grant list, access log and visibility audit. Module-internal, like
 * {@link WorkflowAuthoring}: only this module's controllers can hand it an admin {@link Caller}.
 */
@Service
public class WorkflowSharingService {

    static final int MAX_PAGE_SIZE = 100;
    static final Duration TOTAL_WINDOW = Duration.ofDays(7);

    private final WorkflowServiceImpl arbiter;
    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository workflowStepRepository;
    private final WorkflowLinkGrantRepository grantRepository;
    private final WorkflowAccessLogRepository accessLogRepository;
    private final WorkflowVisibilityAuditRepository auditRepository;
    private final WorkflowAccessLog accessLog;
    private final UserDirectory userDirectory;
    private final Clock clock;

    WorkflowSharingService(WorkflowServiceImpl arbiter, WorkflowRepository workflowRepository,
            WorkflowStepRepository workflowStepRepository, WorkflowLinkGrantRepository grantRepository,
            WorkflowAccessLogRepository accessLogRepository, WorkflowVisibilityAuditRepository auditRepository,
            WorkflowAccessLog accessLog, UserDirectory userDirectory, Clock clock) {
        this.arbiter = arbiter;
        this.workflowRepository = workflowRepository;
        this.workflowStepRepository = workflowStepRepository;
        this.grantRepository = grantRepository;
        this.accessLogRepository = accessLogRepository;
        this.auditRepository = auditRepository;
        this.accessLog = accessLog;
        this.userDirectory = userDirectory;
        this.clock = clock;
    }

    /**
     * Moves the workflow to {@code target}: {@code unlisted} mints a key unless an unexpired one exists,
     * {@code private} and {@code public} clear the key and every grant. {@code expiresAt} is valid only with
     * {@code unlisted} and must be an ISO-8601 instant in the future.
     */
    @Transactional
    public ShareSettingsView setVisibility(Caller caller, UUID workflowId, WorkflowVisibility target,
            String expiresAtText, ShareOrigin origin) {
        Objects.requireNonNull(target, "target");
        Workflow workflow = arbiter.lockForWrite(caller, workflowId);
        Instant now = clock.instant();
        Instant expiresAt = parseExpiry(expiresAtText);
        requireValidExpiry(target, expiresAt, now);
        WorkflowVisibility from = workflow.getVisibility();

        if (target == WorkflowVisibility.UNLISTED) {
            if (from == WorkflowVisibility.UNLISTED && keyIsLive(workflow, now)) {
                workflow.keepKey(expiresAt != null ? expiresAt : workflow.getUnlistedKeyExpiresAt());
            } else {
                workflow.makeUnlisted(ShareKeys.mint(), now, expiresAt);
            }
            workflowRepository.save(workflow);
        } else if (from != target) {
            workflow.makeListedOrPrivate(target);
            workflowRepository.save(workflow);
            if (from == WorkflowVisibility.UNLISTED) {
                grantRepository.deleteByWorkflowId(workflowId);
            }
        }
        if (from != target) {
            audit(workflowId, caller.userId(), from, target,
                    caller.admin() ? VisibilityChangeTrigger.ADMIN : VisibilityChangeTrigger.OWNER, origin, now);
        }
        return settingsOf(workflow);
    }

    /**
     * Replaces the key of an unlisted workflow; the expiry and every grant stay. An expired key is refused: the new
     * key would answer 410 at once, so the owner sets the visibility again with a new expiry instead.
     */
    @Transactional
    public ShareSettingsView regenerateKey(Caller caller, UUID workflowId, ShareOrigin origin) {
        Workflow workflow = arbiter.lockForWrite(caller, workflowId);
        if (workflow.getVisibility() != WorkflowVisibility.UNLISTED) {
            throw new ConflictException(new LocalizedMessage("workflow.share.notUnlisted"));
        }
        Instant now = clock.instant();
        if (!keyIsLive(workflow, now)) {
            throw new ConflictException(new LocalizedMessage("workflow.share.regenerateExpired"));
        }
        workflow.rotateKey(ShareKeys.mint(), now);
        workflowRepository.save(workflow);
        audit(workflowId, caller.userId(), WorkflowVisibility.UNLISTED, WorkflowVisibility.UNLISTED,
                VisibilityChangeTrigger.KEY_REGENERATED, origin, now);
        return settingsOf(workflow);
    }

    @Transactional(readOnly = true)
    public ShareSettingsView sharingSettings(Caller caller, UUID workflowId) {
        return settingsOf(arbiter.loadForOwnerRead(caller, workflowId));
    }

    /**
     * Admits a signed-in non-owner to the workflow through its key: records the grant, then the access-log entry.
     * The owner presenting the key and an anonymous caller get no grant.
     * @throws NotFoundException for an unknown key, a key of another workflow, a draft or a deleted workflow
     * @throws LocalizedStatusException with 410 for an expired key
     */
    @Transactional
    public void admitByKey(UUID callerId, UUID workflowId, String key, ShareOrigin origin) {
        Instant now = clock.instant();
        Workflow workflow = resolveKey(workflowId, key, now);
        if (workflow.getOwnerId().equals(callerId)) {
            return;
        }
        if (callerId != null) {
            grantRepository.admit(workflow.getId(), callerId, key, now);
        }
        accessLog.record(workflow.getId(), callerId, origin);
    }

    /** The narrowed view a share link opens; records the access-log entry and writes no grant. */
    @Transactional(readOnly = true)
    public SharedWorkflowView sharedView(UUID callerIdOrNull, String key, ShareOrigin origin) {
        Workflow workflow = resolveKey(null, key, clock.instant());
        if (!workflow.getOwnerId().equals(callerIdOrNull)) {
            accessLog.record(workflow.getId(), callerIdOrNull, origin);
        }
        String authorName = userDirectory.findNamesByIds(List.of(workflow.getOwnerId())).get(workflow.getOwnerId());
        List<SharedWorkflowView.Step> steps = workflowStepRepository
                .findByWorkflowIdOrderByStepOrderAsc(workflow.getId()).stream()
                .map(step -> new SharedWorkflowView.Step(step.getStepOrder(), step.getName(), step.getType()))
                .toList();
        return new SharedWorkflowView(workflow.getId(), workflow.getName(), authorName, steps);
    }

    @Transactional(readOnly = true)
    public SharePage<LinkGrantView> linkGrants(Caller caller, UUID workflowId, int limit, String cursor) {
        arbiter.loadForOwnerRead(caller, workflowId);
        PageStart start = pageStart(limit, cursor);
        List<WorkflowLinkGrant> rows = start == null
                ? grantRepository.firstPage(workflowId, limit + 1)
                : grantRepository.page(workflowId, start.at(), start.id(), limit + 1);
        return page(rows, limit, WorkflowLinkGrant::getUserId, WorkflowLinkGrant::getCreatedAt,
                WorkflowLinkGrant::getUserId, null, (grant, names) -> new LinkGrantView(grant.getUserId(),
                        names.get(grant.getUserId()), grant.getCreatedAt()));
    }

    /** Removes one grant; returns 1, or 0 when the user held none. */
    @Transactional
    public int revokeGrant(Caller caller, UUID workflowId, UUID userId) {
        arbiter.lockForWrite(caller, workflowId);
        return grantRepository.deleteByWorkflowIdAndUserId(workflowId, userId);
    }

    /** Removes every grant; the key stays valid. */
    @Transactional
    public int revokeAllGrants(Caller caller, UUID workflowId) {
        arbiter.lockForWrite(caller, workflowId);
        return grantRepository.deleteByWorkflowId(workflowId);
    }

    @Transactional(readOnly = true)
    public SharePage<AccessLogEntryView> accessLog(Caller caller, UUID workflowId, int limit, String cursor) {
        arbiter.loadForOwnerRead(caller, workflowId);
        PageStart start = pageStart(limit, cursor);
        List<WorkflowAccessLogEntry> rows = start == null
                ? accessLogRepository.firstPage(workflowId, limit + 1)
                : accessLogRepository.page(workflowId, start.at(), start.id(), limit + 1);
        long total7d = accessLogRepository.countByWorkflowIdAndCreatedAtGreaterThanEqual(workflowId,
                clock.instant().minus(TOTAL_WINDOW));
        return page(rows, limit, WorkflowAccessLogEntry::getId, WorkflowAccessLogEntry::getCreatedAt,
                WorkflowAccessLogEntry::getAccessedBy, total7d, (entry, names) -> new AccessLogEntryView(entry.getId(),
                        entry.getCreatedAt(), entry.getAccessedBy(), names.get(entry.getAccessedBy()),
                        entry.getNetwork(), entry.getUserAgent()));
    }

    @Transactional(readOnly = true)
    public SharePage<VisibilityAuditEntryView> visibilityAudit(Caller caller, UUID workflowId, int limit,
            String cursor) {
        arbiter.loadForOwnerRead(caller, workflowId);
        PageStart start = pageStart(limit, cursor);
        List<WorkflowVisibilityAuditEntry> rows = start == null
                ? auditRepository.firstPage(workflowId, limit + 1)
                : auditRepository.page(workflowId, start.at(), start.id(), limit + 1);
        return page(rows, limit, WorkflowVisibilityAuditEntry::getId, WorkflowVisibilityAuditEntry::getCreatedAt,
                WorkflowVisibilityAuditEntry::getChangedBy, null, (entry, names) -> new VisibilityAuditEntryView(
                        entry.getId(), entry.getCreatedAt(), entry.getChangedBy(), names.get(entry.getChangedBy()),
                        entry.getFromVisibility(), entry.getToVisibility(),
                        entry.getTriggeredBy().name().toLowerCase(Locale.ROOT), entry.getNetwork(),
                        entry.getUserAgent()));
    }

    private Workflow resolveKey(UUID workflowIdOrNull, String key, Instant now) {
        Object subject = workflowIdOrNull != null ? workflowIdOrNull : "link";
        if (key == null || key.isBlank()) {
            throw new NotFoundException(new LocalizedMessage("workflow.notFound", subject));
        }
        Workflow workflow = workflowRepository.findByUnlistedKey(key)
                .filter(found -> workflowIdOrNull == null || found.getId().equals(workflowIdOrNull))
                .filter(found -> found.getDeletedAt() == null && found.getStatus() == WorkflowStatus.PUBLISHED
                        && found.getVisibility() == WorkflowVisibility.UNLISTED)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.notFound", subject)));
        if (!keyIsLive(workflow, now)) {
            throw new LocalizedStatusException(HttpStatus.GONE, new LocalizedMessage("workflow.share.keyExpired"));
        }
        return workflow;
    }

    private static boolean keyIsLive(Workflow workflow, Instant now) {
        Instant expiresAt = workflow.getUnlistedKeyExpiresAt();
        return expiresAt == null || expiresAt.isAfter(now);
    }

    private static Instant parseExpiry(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw invalidExpiry();
        }
    }

    private static void requireValidExpiry(WorkflowVisibility target, Instant expiresAt, Instant now) {
        if (expiresAt != null && (target != WorkflowVisibility.UNLISTED || !expiresAt.isAfter(now))) {
            throw invalidExpiry();
        }
    }

    private static UnprocessableEntityException invalidExpiry() {
        LocalizedMessage reason = new LocalizedMessage("workflow.share.expiresAtInvalid");
        return new UnprocessableEntityException(reason, null, Map.of("expiresAt", reason));
    }

    private void audit(UUID workflowId, UUID changedBy, WorkflowVisibility from, WorkflowVisibility to,
            VisibilityChangeTrigger trigger, ShareOrigin origin, Instant now) {
        auditRepository.save(new WorkflowVisibilityAuditEntry(UUID.randomUUID(), workflowId, changedBy, from, to,
                trigger, origin.network(), origin.userAgent(), now));
    }

    private static ShareSettingsView settingsOf(Workflow workflow) {
        return new ShareSettingsView(workflow.getVisibility(), workflow.getUnlistedKey(),
                workflow.getUnlistedKeyGeneratedAt(), workflow.getUnlistedKeyExpiresAt());
    }

    /**
     * Cuts the {@code limit + 1} fetched rows to one page, resolves the names of the people in it with one
     * lookup, and builds the cursor from the last row of the page when more remain.
     */
    private <E, V> SharePage<V> page(List<E> rows, int limit, Function<E, UUID> idOf, Function<E, Instant> timeOf,
            Function<E, UUID> personOf, Long total7d, BiFunction<E, Map<UUID, String>, V> view) {
        boolean hasMore = rows.size() > limit;
        List<E> pageRows = hasMore ? rows.subList(0, limit) : rows;
        List<UUID> people = pageRows.stream().map(personOf).filter(Objects::nonNull).distinct().toList();
        Map<UUID, String> names = people.isEmpty() ? Map.of() : userDirectory.findNamesByIds(people);
        List<V> data = pageRows.stream().map(row -> view.apply(row, names)).toList();
        String next = null;
        if (hasMore) {
            E last = pageRows.get(pageRows.size() - 1);
            next = encodeCursor(timeOf.apply(last), idOf.apply(last));
        }
        return new SharePage<>(data, hasMore, next, total7d);
    }

    /** The keyset position to continue after, or {@code null} for the first page. */
    private static PageStart pageStart(int limit, String cursor) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw pageInvalid("limit");
        }
        if (cursor == null) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 1) {
                throw pageInvalid("cursor");
            }
            long micros = Long.parseLong(decoded.substring(0, colon));
            UUID id = UUID.fromString(decoded.substring(colon + 1));
            return new PageStart(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                    Math.floorMod(micros, 1_000_000L) * 1_000L), id);
        } catch (IllegalArgumentException e) {
            throw pageInvalid("cursor");
        }
    }

    private static String encodeCursor(Instant at, UUID id) {
        long micros = at.getEpochSecond() * 1_000_000L + at.getNano() / 1_000L;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((micros + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static LocalizedStatusException pageInvalid(String parameter) {
        return new LocalizedStatusException(HttpStatus.BAD_REQUEST,
                new LocalizedMessage("workflow.share.pageInvalid", parameter));
    }

    private record PageStart(Instant at, UUID id) {
    }
}
