package com.flowcortex.support.request;

import com.flowcortex.notification.api.NotificationRequested;
import com.flowcortex.support.request.SlaAlertRepository.SlaRow;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * What the SLA sweep alerts for one request, who hears it, and the notification each recipient gets. Pure: the
 * sweep owns the claim, the re-check and the publishing.
 */
final class SlaAlerts {

    /** A crossing older than this is a backlog (a threshold lowered, or the sweep switched on), never an alert. */
    static final Duration FRESH_WINDOW = Duration.ofHours(24);

    static final String TYPE = "admin_support_sla";

    private static final String DUE_SOON_TITLE = "notification.admin.supportSla.dueSoon.title";
    private static final String OVERDUE_TITLE = "notification.admin.supportSla.overdue.title";
    private static final String BODY = "notification.admin.supportSla.body";

    private SlaAlerts() {
    }

    /**
     * @param claim the levels the alert marks as told: an overdue alert also marks the warning, so it never follows
     * @param emitted the level the alert announces
     * @param assigneeId the planned recipient key the claim is stored under; null means every holder
     */
    record Plan(List<SlaLevel> claim, SlaLevel emitted, UUID assigneeId) {
    }

    static Optional<Plan> plan(SlaRow row, SlaThresholds thresholds, Instant now) {
        SlaLevel level = SlaLevel.of(row.status(), row.lastActivityAt(), now, thresholds);
        if (!level.atRisk()) {
            return Optional.empty();
        }
        Instant crossedAt = row.lastActivityAt().plus(crossingAge(row.status(), level, thresholds));
        if (Duration.between(crossedAt, now).compareTo(FRESH_WINDOW) > 0) {
            return Optional.empty();
        }
        List<SlaLevel> claim = level == SlaLevel.OVERDUE
                ? List.of(SlaLevel.DUE_SOON, SlaLevel.OVERDUE) : List.of(SlaLevel.DUE_SOON);
        return Optional.of(new Plan(claim, level, row.assigneeId()));
    }

    /** The assignee of an in-progress request while they can view requests; every holder otherwise. */
    static List<UUID> recipients(SlaRow row, Predicate<UUID> holdsView, List<UUID> holders) {
        if (row.status() == RequestStatus.IN_PROGRESS && row.assigneeId() != null
                && holdsView.test(row.assigneeId())) {
            return List.of(row.assigneeId());
        }
        return holders;
    }

    static NotificationRequested event(SlaRow row, SlaLevel level, UUID recipient) {
        String seed = "support-sla:" + row.id() + ":" + level.value() + ":" + row.lastActivityAt().toEpochMilli()
                + ":" + recipient;
        return new NotificationRequested(UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)), recipient,
                TYPE, level == SlaLevel.OVERDUE ? OVERDUE_TITLE : DUE_SOON_TITLE, BODY,
                List.of(row.number(), row.subject()), "/support/requests/" + row.number());
    }

    private static Duration crossingAge(RequestStatus status, SlaLevel level, SlaThresholds thresholds) {
        boolean overdue = level == SlaLevel.OVERDUE;
        return status == RequestStatus.NEW
                ? (overdue ? thresholds.newLimit() : thresholds.newWarnCut())
                : (overdue ? thresholds.inProgressLimit() : thresholds.inProgressWarnCut());
    }
}
