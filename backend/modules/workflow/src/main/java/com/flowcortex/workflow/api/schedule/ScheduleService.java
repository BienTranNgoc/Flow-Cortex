package com.flowcortex.workflow.api.schedule;

import com.flowcortex.shared.web.PageView;
import com.flowcortex.workflow.api.RunView;
import java.util.UUID;

/**
 * A member's own schedule for a workflow. Every method acts only on the caller's schedule and fires, and
 * answers 503 {@code feature.turnedOff} while {@code flowcortex.workflow.schedules.enabled} is false.
 */
public interface ScheduleService {

    /** Creates or replaces the caller's schedule for the workflow. */
    ScheduleView set(UUID callerId, UUID workflowId, SetSchedule schedule);

    ScheduleView get(UUID callerId, UUID workflowId);

    /** Pauses an active schedule; a paused one is answered unchanged. */
    ScheduleView pause(UUID callerId, UUID workflowId);

    /** Resumes a paused schedule with its next fire computed from now; an active one is answered unchanged. */
    ScheduleView resume(UUID callerId, UUID workflowId);

    void delete(UUID callerId, UUID workflowId);

    /** Starts one run for the schedule's input now, recorded as a {@code manual} fire. */
    RunView runNow(UUID callerId, UUID workflowId);

    /** The caller's fires of the workflow, newest first. */
    PageView<ScheduleFireView> history(UUID callerId, UUID workflowId, int page, int size);
}
