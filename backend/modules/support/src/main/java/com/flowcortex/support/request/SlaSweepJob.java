package com.flowcortex.support.request;

import com.flowcortex.jobs.api.ClusterJob;
import com.flowcortex.settings.api.Settings;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.support.config.SupportSlaProperties;
import com.flowcortex.support.config.SupportSlaSettings;
import com.flowcortex.support.request.SlaAlertRepository.SlaCuts;
import com.flowcortex.support.request.SlaAlertRepository.SlaRow;
import com.flowcortex.support.request.SlaAlerts.Plan;
import com.flowcortex.user.api.AdminAssignee;
import com.flowcortex.user.api.AdminPermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rings the admin bell once when a {@code new} or {@code in_progress} request crosses its SLA warning or limit.
 * Each alert claims its (request, level, last activity, assignee) in the ledger, re-reads the request under a row
 * lock and publishes one notification per recipient in one transaction, so a request an admin changed meanwhile
 * publishes nothing and keeps no claim, and a failing alert rolls back alone. A run reads at most {@value #BATCH}
 * fresh, unalerted crossings; while the alerts are switched off it only deletes old claims. Runs on one instance
 * at a time through the cluster runner.
 */
@Component
public class SlaSweepJob implements ClusterJob {

    static final int BATCH = 500;
    static final Duration CLAIM_RETENTION = Duration.ofDays(30);

    private static final Logger LOG = LoggerFactory.getLogger(SlaSweepJob.class);

    private enum Outcome { ALERTED, ALREADY_ALERTED, STATE_CHANGED, NO_RECIPIENT }

    private final SlaAlertRepository alerts;
    private final Settings settings;
    private final AdminPermissions permissions;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration interval;

    SlaSweepJob(SlaAlertRepository alerts, Settings settings, AdminPermissions permissions,
            ApplicationEventPublisher events, PlatformTransactionManager transactionManager, Clock clock,
            SupportSlaProperties properties) {
        this.alerts = alerts;
        this.settings = settings;
        this.permissions = permissions;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.interval = properties.sweepInterval();
    }

    @Override
    public String name() {
        return "support.sla-sweep";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public void run() {
        Instant now = Instant.now(clock);
        int pruned = alerts.pruneBefore(now.minus(CLAIM_RETENTION));
        if (!settings.get(SupportSlaSettings.ALERTS_ENABLED).effective()) {
            LOG.info("support.sla.sweep skipped=disabled pruned={}", pruned);
            return;
        }
        SlaThresholds thresholds = SlaThresholds.read(settings);
        List<SlaRow> candidates = alerts.candidates(SlaCuts.at(now, thresholds), BATCH);
        List<UUID> holders = candidates.isEmpty() ? List.of() : holders();
        Map<UUID, Boolean> holdsView = new HashMap<>();
        int alerted = 0;
        int skipped = 0;
        int stale = 0;
        int failed = 0;
        for (SlaRow row : candidates) {
            Optional<Plan> plan = SlaAlerts.plan(row, thresholds, now);
            if (plan.isEmpty()) {
                stale++;
                continue;
            }
            try {
                Outcome outcome = transaction.execute(status -> {
                    Outcome result = alert(row, plan.get(), holders, holdsView);
                    if (result != Outcome.ALERTED) {
                        status.setRollbackOnly();
                    }
                    return result;
                });
                if (outcome == Outcome.ALERTED) {
                    alerted++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                failed++;
                LOG.warn("support.sla.alert outcome=failed number={}", row.number(), e);
            }
        }
        LOG.info("support.sla.sweep scanned={} alerted={} skipped={} stale={} failed={} pruned={}",
                candidates.size(), alerted, skipped, stale, failed, pruned);
    }

    private Outcome alert(SlaRow row, Plan plan, List<UUID> holders, Map<UUID, Boolean> holdsView) {
        List<SlaLevel> claimed = alerts.claim(row.id(), plan.claim(), row.lastActivityAt(), plan.assigneeId());
        if (!claimed.contains(plan.emitted())) {
            return Outcome.ALREADY_ALERTED;
        }
        if (alerts.lockUnchanged(row).isEmpty()) {
            LOG.info("support.sla.alert outcome=state_changed number={}", row.number());
            return Outcome.STATE_CHANGED;
        }
        List<UUID> recipients = SlaAlerts.recipients(row,
                id -> holdsView.computeIfAbsent(id, who -> permissions.holds(who, Permission.SUPPORT_TICKET_VIEW)),
                holders);
        if (recipients.isEmpty()) {
            LOG.info("support.sla.alert outcome=no_recipient number={}", row.number());
            return Outcome.NO_RECIPIENT;
        }
        for (UUID recipient : recipients) {
            events.publishEvent(SlaAlerts.event(row, plan.emitted(), recipient));
        }
        return Outcome.ALERTED;
    }

    private List<UUID> holders() {
        return permissions.holders(Permission.SUPPORT_TICKET_VIEW).stream().map(AdminAssignee::id).toList();
    }
}
