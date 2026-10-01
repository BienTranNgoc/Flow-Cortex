package com.flowcortex.workflow.application;

import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes every running step whose bound passed, so no step stays open after its executor died:
 * never picked up by the queue deadline → {@code queue_timeout}; picked up but no heartbeat within
 * the lease → {@code lease_expired}; picked up and past the run deadline → {@code run_timeout}.
 * The failure is the same CAS a result uses, so a result that lands first wins and the step is
 * neither counted nor touched. Runs in every instance; the CAS makes concurrent reapers safe.
 *
 * <p>Each pass then re-advances running runs with no step in flight, which is how a run whose
 * advance was lost after a step completed (a crash, a failed listener) gets moving again.
 * A pass handles at most {@link #BATCH_SIZE} of each; the next pass takes the rest, starting
 * with the runs swept longest ago.
 */
@Component
class LeaseReaper {

    static final int BATCH_SIZE = 100;

    private static final Logger LOG = LoggerFactory.getLogger(LeaseReaper.class);

    private final RunStepRepository runStepRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final RunStepTransactions transactions;
    private final StepExecutorRegistry registry;
    private final RunAdvancer advancer;
    // Registered up front so every code × mode series exists at 0 before the first reap.
    private final Map<Reason, Counter> reapedInline = new EnumMap<>(Reason.class);
    private final Map<Reason, Counter> reapedRemote = new EnumMap<>(Reason.class);
    private final Clock clock;

    @Autowired
    LeaseReaper(RunStepRepository runStepRepository, WorkflowRunRepository workflowRunRepository,
            RunStepTransactions transactions, StepExecutorRegistry registry, RunAdvancer advancer,
            MeterRegistry meters) {
        this(runStepRepository, workflowRunRepository, transactions, registry, advancer, meters, Clock.systemUTC());
    }

    LeaseReaper(RunStepRepository runStepRepository, WorkflowRunRepository workflowRunRepository,
            RunStepTransactions transactions, StepExecutorRegistry registry, RunAdvancer advancer,
            MeterRegistry meters, Clock clock) {
        this.runStepRepository = runStepRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.transactions = transactions;
        this.registry = registry;
        this.advancer = advancer;
        for (Reason reason : Reason.values()) {
            reapedInline.put(reason, reapedCounter(meters, reason, "inline"));
            reapedRemote.put(reason, reapedCounter(meters, reason, "remote"));
        }
        this.clock = clock;
    }

    private static Counter reapedCounter(MeterRegistry meters, Reason reason, String mode) {
        return Counter.builder("media.reaper.reaped")
                .description("Running steps the reaper closed, by the bound they passed")
                .tag("code", reason.code)
                .tag("mode", mode)
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${flowcortex.workflow.reaper-interval-ms:30000}")
    void reap() {
        Instant now = clock.instant();
        for (RunStep step : runStepRepository.findReapable(now, PageRequest.of(0, BATCH_SIZE))) {
            try (StepLogContext ignored = StepLogContext.step(step.getRunId(), step.getId(),
                    registry.isRemote(step.getType()), step.getAttempt(), step.getDeliveryCount())) {
                close(step, now);
            } catch (RuntimeException e) {
                LOG.error("Could not close step: runId={} runStepId={} — retrying next pass", step.getRunId(),
                        step.getId(), e);
            }
        }
        List<UUID> stalled = workflowRunRepository.findStalledRunIds(PageRequest.of(0, BATCH_SIZE));
        if (!stalled.isEmpty()) {
            // Before advancing: a run whose advance throws or changes nothing still moves to the back.
            workflowRunRepository.markSwept(stalled, now);
        }
        for (UUID runId : stalled) {
            try {
                advancer.advance(runId);
            } catch (RuntimeException e) {
                LOG.error("Could not re-advance run {}; retrying next pass", runId, e);
            }
        }
    }

    private void close(RunStep step, Instant now) {
        Optional<Reason> reason = Reason.of(step, now);
        if (reason.isEmpty()) {
            return;
        }
        Reason r = reason.get();
        String message = r.message(registry.find(step.getType()).map(executor -> executor.maxRuntime().toSeconds()));
        if (transactions.fail(step.getRunId(), step.getId(), step.getAttempt(), r.code, message, now)) {
            (registry.isRemote(step.getType()) ? reapedRemote : reapedInline).get(r).increment();
            LOG.warn("Step reaped: runId={} runStepId={} type={} attempt={} deliveryCount={} code={}", step.getRunId(),
                    step.getId(), step.getType(), step.getAttempt(), step.getDeliveryCount(), r.code);
        }
    }

    /** Which bound a running step passed; when two have, the one that passed first. */
    enum Reason {
        QUEUE_TIMEOUT("queue_timeout"),
        LEASE_EXPIRED("lease_expired"),
        RUN_TIMEOUT("run_timeout");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        String message(Optional<Long> maxRuntimeSeconds) {
            return switch (this) {
                case QUEUE_TIMEOUT -> "not picked up within the queue timeout";
                case LEASE_EXPIRED -> "no heartbeat from the executor within the lease";
                case RUN_TIMEOUT -> maxRuntimeSeconds
                        .map(seconds -> "exceeded the maximum run time of " + seconds + " s")
                        .orElse("exceeded the maximum run time");
            };
        }

        static Optional<Reason> of(RunStep step, Instant now) {
            if (step.getPickedUpAt() == null) {
                return passed(step.getQueueDeadline(), now) ? Optional.of(QUEUE_TIMEOUT) : Optional.empty();
            }
            boolean leasePassed = passed(step.getLeaseUntil(), now);
            boolean runPassed = passed(step.getRunDeadline(), now);
            if (leasePassed && runPassed) {
                return Optional.of(step.getRunDeadline().isBefore(step.getLeaseUntil()) ? RUN_TIMEOUT : LEASE_EXPIRED);
            }
            if (leasePassed) {
                return Optional.of(LEASE_EXPIRED);
            }
            return runPassed ? Optional.of(RUN_TIMEOUT) : Optional.empty();
        }

        private static boolean passed(Instant deadline, Instant now) {
            return deadline != null && deadline.isBefore(now);
        }
    }
}
