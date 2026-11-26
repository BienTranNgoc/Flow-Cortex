package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowRunService;
import com.flowcortex.workflow.application.ScheduleFireErrors.Described;
import com.flowcortex.workflow.domain.ScheduleFire;
import com.flowcortex.workflow.domain.WorkflowSchedule;
import com.flowcortex.workflow.infrastructure.ScheduleFireRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowScheduleRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The record of each attempt to start a run for a schedule. A fire is inserted {@code starting} before its run
 * exists and moved once, to {@code started} or {@code failed}, after the start path answered; each step is its own
 * short transaction, and the start path runs outside all of them, so a crash between the run's commit and the
 * update leaves a {@code starting} row that the history joins to its run by request key.
 */
@Component
class ScheduleFires {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduleFires.class);
    private static final String KEY_PREFIX = "schedule:";

    /** A recorded fire and the run request key it starts its run under. */
    record Recorded(UUID id, String requestKey) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduleFireRepository fires;
    private final WorkflowScheduleRepository schedules;
    private final WorkflowRepository workflows;
    private final WorkflowRunService runs;
    private final Clock clock;

    ScheduleFires(ScheduleFireRepository fires, WorkflowScheduleRepository schedules, WorkflowRepository workflows,
            WorkflowRunService runs, Clock clock) {
        this.fires = fires;
        this.schedules = schedules;
        this.workflows = workflows;
        this.runs = runs;
        this.clock = clock;
    }

    /**
     * Inserts a {@code starting} fire of the owner's schedule.
     *
     * @throws NotFoundException {@code workflow.schedule.notFound} when the schedule is gone or not the owner's
     */
    Recorded record(UUID ownerId, UUID scheduleId, String trigger, Instant scheduledAt) {
        UUID id = UUID.randomUUID();
        String requestKey = KEY_PREFIX + id;
        if (fires.recordForSchedule(id, scheduleId, ownerId, trigger, scheduledAt, requestKey, clock.instant()) == 0) {
            throw new NotFoundException(new LocalizedMessage("workflow.schedule.notFound", scheduleId));
        }
        return new Recorded(id, requestKey);
    }

    void markStarted(UUID fireId) {
        fires.markStarted(fireId);
    }

    void markFailed(UUID fireId, Described failure) {
        fires.markFailed(fireId, failure.code(), failure.messageKey(), argsJson(failure));
    }

    /**
     * Starts one run of the caller's schedule now: the schedule's input and cost acknowledgement, through the run
     * start path, as a {@code manual} fire. The schedule's status and next fire are not touched. A refusal of the
     * start path is recorded on the fire and rethrown unchanged, so the caller is answered as {@code POST …/runs}
     * would answer.
     *
     * @throws NotFoundException {@code workflow.schedule.notFound} without a schedule, {@code workflow.notFound}
     *     when the workflow is no longer visible to the caller
     */
    RunView runNow(UUID ownerId, UUID workflowId) {
        WorkflowSchedule schedule = schedules.findByOwnerIdAndWorkflowId(ownerId, workflowId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.schedule.notFound", workflowId)));
        workflows.findVisibleTo(workflowId, ownerId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId)));
        Recorded fire = record(ownerId, schedule.getId(), ScheduleFire.MANUAL, clock.instant());
        RunView run;
        try {
            run = runs.startRun(ownerId, workflowId, schedule.getInput(), null, null, null,
                    schedule.isAcknowledgeCost(), fire.requestKey());
        } catch (RuntimeException refusal) {
            try {
                markFailed(fire.id(), ScheduleFireErrors.describe(refusal));
            } catch (RuntimeException recording) {
                LOG.error("Fire {} could not be marked failed; its history row will report an interrupted start",
                        fire.id(), recording);
                refusal.addSuppressed(recording);
            }
            throw refusal;
        }
        try {
            markStarted(fire.id());
        } catch (RuntimeException recording) {
            // The run exists; the history finds it through the request key whatever the row says.
            LOG.warn("Fire {} could not be marked started; its history row is reported from its run", fire.id(),
                    recording);
        }
        return run;
    }

    private String argsJson(Described failure) {
        try {
            return objectMapper.writeValueAsString(failure.args());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Message arguments of " + failure.messageKey() + " are not JSON", e);
        }
    }
}
