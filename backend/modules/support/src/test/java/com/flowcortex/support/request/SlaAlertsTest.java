package com.flowcortex.support.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.notification.api.NotificationRequested;
import com.flowcortex.support.request.SlaAlertRepository.SlaRow;
import com.flowcortex.support.request.SlaAlerts.Plan;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What the SLA sweep plans for one request, who it tells, and the notification it raises. */
class SlaAlertsTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final SlaThresholds DEFAULTS = new SlaThresholds(24, 20, 3, 2);
    private static final SlaThresholds WIDE = new SlaThresholds(72, 20, 10, 2);
    private static final UUID LINH = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID MINH = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-000000000081");

    private static SlaRow row(RequestStatus status, Duration age, UUID assignee) {
        return new SlaRow(REQUEST, 81, "Không tải được video", status, assignee, NOW.minus(age));
    }

    @Test
    void aFreshDueSoonCrossingPlansDueSoon() {
        Optional<Plan> plan = SlaAlerts.plan(row(RequestStatus.NEW, Duration.ofHours(20).plusMinutes(3), null),
                DEFAULTS, NOW);

        assertThat(plan).contains(new Plan(List.of(SlaLevel.DUE_SOON), SlaLevel.DUE_SOON, null));
    }

    @Test
    void anOverduePlansBothLevelsAndEmitsOverdue() {
        Optional<Plan> plan = SlaAlerts.plan(row(RequestStatus.NEW, Duration.ofHours(25), null), DEFAULTS, NOW);

        assertThat(plan).contains(new Plan(List.of(SlaLevel.DUE_SOON, SlaLevel.OVERDUE), SlaLevel.OVERDUE, null));
    }

    @Test
    void aCrossingOverADayAgoIsStale() {
        // due soon since 20 h, still within the 72 h limit: the warning crossed 24 h 1 s ago
        assertThat(SlaAlerts.plan(row(RequestStatus.NEW, Duration.ofHours(44).plusSeconds(1), null), WIDE, NOW))
                .isEmpty();
        assertThat(SlaAlerts.plan(row(RequestStatus.IN_PROGRESS,
                Duration.ofDays(2).plusHours(24).plusSeconds(1), LINH), WIDE, NOW)).isEmpty();
        // overdue since 24 h: the limit crossed 24 h 1 s ago, and 3 days ago for a backlog
        assertThat(SlaAlerts.plan(row(RequestStatus.NEW, Duration.ofHours(48).plusSeconds(1), null), DEFAULTS, NOW))
                .isEmpty();
        assertThat(SlaAlerts.plan(row(RequestStatus.NEW, Duration.ofDays(3), null), DEFAULTS, NOW)).isEmpty();
        assertThat(SlaAlerts.plan(row(RequestStatus.IN_PROGRESS, Duration.ofDays(4).plusSeconds(1), LINH),
                DEFAULTS, NOW)).isEmpty();
    }

    @Test
    void exactlyADayAgoIsStillFresh() {
        assertThat(SlaAlerts.plan(row(RequestStatus.NEW, Duration.ofHours(44), null), WIDE, NOW))
                .hasValueSatisfying(plan -> assertThat(plan.emitted()).isEqualTo(SlaLevel.DUE_SOON));
        assertThat(SlaAlerts.plan(row(RequestStatus.NEW, Duration.ofHours(48), null), DEFAULTS, NOW))
                .hasValueSatisfying(plan -> assertThat(plan.emitted()).isEqualTo(SlaLevel.OVERDUE));
        assertThat(SlaAlerts.plan(row(RequestStatus.IN_PROGRESS, Duration.ofDays(4), LINH), DEFAULTS, NOW))
                .hasValueSatisfying(plan -> assertThat(plan.emitted()).isEqualTo(SlaLevel.OVERDUE));
    }

    @Test
    void aPausedOrOkRequestPlansNothing() {
        assertThat(SlaAlerts.plan(row(RequestStatus.NEW, Duration.ofHours(10), null), DEFAULTS, NOW)).isEmpty();
        assertThat(SlaAlerts.plan(row(RequestStatus.NEW, Duration.ofHours(20), null), DEFAULTS, NOW)).isEmpty();
        assertThat(SlaAlerts.plan(row(RequestStatus.WAITING_MEMBER, Duration.ofHours(25), null), DEFAULTS, NOW))
                .isEmpty();
        assertThat(SlaAlerts.plan(row(RequestStatus.RESOLVED, Duration.ofHours(25), null), DEFAULTS, NOW))
                .isEmpty();
        assertThat(SlaAlerts.plan(row(RequestStatus.CLOSED, Duration.ofHours(25), null), DEFAULTS, NOW)).isEmpty();
    }

    @Test
    void aWarningAtOrAboveItsLimitPlansOverdueOnly() {
        Optional<Plan> plan = SlaAlerts.plan(row(RequestStatus.NEW, Duration.ofHours(25), null),
                new SlaThresholds(24, 30, 3, 2), NOW);

        assertThat(plan).hasValueSatisfying(found -> assertThat(found.emitted()).isEqualTo(SlaLevel.OVERDUE));
        assertThat(SlaAlerts.plan(row(RequestStatus.NEW, Duration.ofHours(23), null),
                new SlaThresholds(24, 30, 3, 2), NOW)).isEmpty();
    }

    @Test
    void recipientsForNewAreTheHolders() {
        assertThat(SlaAlerts.recipients(row(RequestStatus.NEW, Duration.ofHours(21), null), id -> true,
                List.of(LINH, MINH))).containsExactly(LINH, MINH);
    }

    @Test
    void recipientsForInProgressAreTheAssigneeWhileTheyHoldView() {
        assertThat(SlaAlerts.recipients(row(RequestStatus.IN_PROGRESS, Duration.ofDays(3), LINH),
                id -> id.equals(LINH), List.of(LINH, MINH))).containsExactly(LINH);
    }

    @Test
    void anInProgressRequestWithoutAnEligibleAssigneeGoesToTheHolders() {
        assertThat(SlaAlerts.recipients(row(RequestStatus.IN_PROGRESS, Duration.ofDays(3), LINH),
                id -> false, List.of(MINH))).containsExactly(MINH);
        assertThat(SlaAlerts.recipients(row(RequestStatus.IN_PROGRESS, Duration.ofDays(3), null),
                id -> true, List.of(LINH, MINH))).containsExactly(LINH, MINH);
    }

    @Test
    void theNotificationIdIsStablePerRequestLevelCycleAndRecipient() {
        SlaRow row = row(RequestStatus.NEW, Duration.ofHours(25), null);
        UUID id = SlaAlerts.event(row, SlaLevel.OVERDUE, LINH).id();

        assertThat(SlaAlerts.event(row, SlaLevel.OVERDUE, LINH).id()).isEqualTo(id);
        assertThat(SlaAlerts.event(row, SlaLevel.DUE_SOON, LINH).id()).isNotEqualTo(id);
        assertThat(SlaAlerts.event(row, SlaLevel.OVERDUE, MINH).id()).isNotEqualTo(id);
        SlaRow newCycle = row(RequestStatus.NEW, Duration.ofHours(26), null);
        assertThat(SlaAlerts.event(newCycle, SlaLevel.OVERDUE, LINH).id()).isNotEqualTo(id);
        SlaRow otherRequest = new SlaRow(UUID.randomUUID(), 81, row.subject(), row.status(), null,
                row.lastActivityAt());
        assertThat(SlaAlerts.event(otherRequest, SlaLevel.OVERDUE, LINH).id()).isNotEqualTo(id);
    }

    @Test
    void theClaimCarriesTheRowsAssignee() {
        Optional<Plan> reassigned = SlaAlerts.plan(
                row(RequestStatus.IN_PROGRESS, Duration.ofDays(2).plusHours(1), MINH), DEFAULTS, NOW);

        assertThat(reassigned).hasValueSatisfying(plan -> assertThat(plan.assigneeId()).isEqualTo(MINH));
        assertThat(SlaAlerts.plan(row(RequestStatus.NEW, Duration.ofHours(21), null), DEFAULTS, NOW))
                .hasValueSatisfying(plan -> assertThat(plan.assigneeId()).isNull());
    }

    @Test
    void theAlertCarriesTypeCodesSubjectAndLink() {
        SlaRow row = row(RequestStatus.NEW, Duration.ofHours(25), null);

        NotificationRequested overdue = SlaAlerts.event(row, SlaLevel.OVERDUE, LINH);
        assertThat(overdue.memberId()).isEqualTo(LINH);
        assertThat(overdue.type()).isEqualTo("admin_support_sla");
        assertThat(overdue.titleCode()).isEqualTo("notification.admin.supportSla.overdue.title");
        assertThat(overdue.bodyCode()).isEqualTo("notification.admin.supportSla.body");
        assertThat(overdue.args()).containsExactly(81L, "Không tải được video");
        assertThat(overdue.link()).isEqualTo("/support/requests/81");

        assertThat(SlaAlerts.event(row, SlaLevel.DUE_SOON, LINH).titleCode())
                .isEqualTo("notification.admin.supportSla.dueSoon.title");
    }
}
