package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.shared.web.RequestLocale;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.schedule.ScheduleFireView;
import com.flowcortex.workflow.api.schedule.ScheduleFrequency;
import com.flowcortex.workflow.api.schedule.ScheduleService;
import com.flowcortex.workflow.api.schedule.ScheduleView;
import com.flowcortex.workflow.api.schedule.SetSchedule;
import com.flowcortex.workflow.application.ScheduleFireStatus.Reported;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowSchedule;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.ScheduleFireRepository;
import com.flowcortex.workflow.infrastructure.ScheduleFireRow;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowScheduleRepository;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * A member's own schedule for a workflow. A schedule is stored only when the run start path would run it as the
 * member: the same visibility rule, the steps the member is served and the readiness guard are called, not copied.
 * Every write is one conditional statement keyed by the caller, so a member can neither reach nor race another
 * member's row.
 */
@Service
class ScheduleServiceImpl implements ScheduleService {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduleServiceImpl.class);
    private static final TypeReference<List<Object>> ARGS = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkflowScheduleRepository schedules;
    private final ScheduleFireRepository fires;
    private final ScheduleFires scheduleFires;
    private final WorkflowRepository workflows;
    private final ServedDefinitions servedDefinitions;
    private final StepExecutorRegistry registry;
    private final RunStartGuards runStartGuards;
    private final MessageSource messages;
    private final RequestLocale requestLocale;
    private final Clock clock;
    private final boolean enabled;

    ScheduleServiceImpl(WorkflowScheduleRepository schedules, ScheduleFireRepository fires,
            ScheduleFires scheduleFires, WorkflowRepository workflows, ServedDefinitions servedDefinitions,
            StepExecutorRegistry registry, RunStartGuards runStartGuards, MessageSource messages,
            RequestLocale requestLocale, Clock clock,
            @Value("${flowcortex.workflow.schedules.enabled:false}") boolean enabled) {
        this.schedules = schedules;
        this.fires = fires;
        this.scheduleFires = scheduleFires;
        this.workflows = workflows;
        this.servedDefinitions = servedDefinitions;
        this.registry = registry;
        this.runStartGuards = runStartGuards;
        this.messages = messages;
        this.requestLocale = requestLocale;
        this.clock = clock;
        this.enabled = enabled;
    }

    /**
     * @throws IllegalArgumentException for a time zone or a day the frequency needs that the request validation
     *     should have refused
     */
    @Override
    public ScheduleView set(UUID callerId, UUID workflowId, SetSchedule schedule) {
        requireEnabled();
        ZoneId zone = ScheduleClock.parseZone(schedule.timezone());
        checkStartable(callerId, workflowId, schedule.active());
        Integer dayOfWeek = schedule.frequency() == ScheduleFrequency.WEEKLY && schedule.dayOfWeek() != null
                ? schedule.dayOfWeek().getValue() : null;
        Integer dayOfMonth = schedule.frequency() == ScheduleFrequency.MONTHLY ? schedule.dayOfMonth() : null;
        Instant now = clock.instant();
        Instant next = schedule.active()
                ? ScheduleClock.next(schedule.frequency().wire(), dayOfWeek, dayOfMonth, schedule.time(), zone, now)
                : null;
        return view(schedules.upsert(UUID.randomUUID(), callerId, workflowId, schedule.frequency().wire(),
                dayOfWeek == null ? null : dayOfWeek.shortValue(),
                dayOfMonth == null ? null : dayOfMonth.shortValue(), schedule.time(), schedule.timezone(),
                schedule.active(), next, schedule.input(), schedule.acknowledgeCost(), now));
    }

    @Override
    public ScheduleView get(UUID callerId, UUID workflowId) {
        requireEnabled();
        return view(own(callerId, workflowId));
    }

    @Override
    public ScheduleView pause(UUID callerId, UUID workflowId) {
        requireEnabled();
        // No row moved means already paused or not there: the read answers either, a second write never does.
        schedules.pause(callerId, workflowId, clock.instant());
        return view(own(callerId, workflowId));
    }

    @Override
    public ScheduleView resume(UUID callerId, UUID workflowId) {
        requireEnabled();
        WorkflowSchedule current = own(callerId, workflowId);
        if (current.isActive()) {
            return view(current);
        }
        checkStartable(callerId, workflowId, true);
        Instant now = clock.instant();
        Instant next = ScheduleClock.next(current.getFrequency(), toInt(current.getDayOfWeek()),
                toInt(current.getDayOfMonth()), current.getLocalTime(), ZoneId.of(current.getTimeZone()), now);
        schedules.resume(callerId, workflowId, next, now);
        return view(own(callerId, workflowId));
    }

    @Override
    public void delete(UUID callerId, UUID workflowId) {
        requireEnabled();
        if (schedules.deleteByOwnerIdAndWorkflowId(callerId, workflowId) == 0) {
            throw scheduleNotFound(workflowId);
        }
    }

    @Override
    public RunView runNow(UUID callerId, UUID workflowId) {
        requireEnabled();
        return scheduleFires.runNow(callerId, workflowId);
    }

    @Override
    public PageView<ScheduleFireView> history(UUID callerId, UUID workflowId, int page, int size) {
        requireEnabled();
        Page<ScheduleFireRow> rows = fires.findHistory(callerId, workflowId, PageRequest.of(page, size));
        Instant now = clock.instant();
        Locale locale = requestLocale.current();
        return new PageView<>(rows.stream().map(row -> fireView(row, now, locale)).toList(), page, size,
                rows.getTotalElements());
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    new LocalizedMessage("feature.turnedOff"));
        }
    }

    private WorkflowSchedule own(UUID callerId, UUID workflowId) {
        return schedules.findByOwnerIdAndWorkflowId(callerId, workflowId)
                .orElseThrow(() -> scheduleNotFound(workflowId));
    }

    private static NotFoundException scheduleNotFound(UUID workflowId) {
        return new NotFoundException(new LocalizedMessage("workflow.schedule.notFound", workflowId));
    }

    /**
     * The checks of the start path that decide whether a schedule could fire, on the steps the caller is served: the
     * workflow is the caller's to run, its first step takes text (a fire has no upload to give), and, when
     * {@code active}, the readiness guard accepts it.
     */
    private void checkStartable(UUID callerId, UUID workflowId, boolean active) {
        if (ProductVideoWorkflow.ID.equals(workflowId)) {
            throw new LocalizedStatusException(HttpStatus.BAD_REQUEST,
                    new LocalizedMessage("workflow.productVideo.startElsewhere"));
        }
        Workflow workflow = workflows.findVisibleTo(workflowId, callerId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId)));
        List<WorkflowStep> steps = servedDefinitions.stepsFor(workflow, callerId);
        if (!steps.isEmpty() && registry.isRemote(steps.getFirst().getType())) {
            throw new UnprocessableEntityException(new LocalizedMessage("workflow.schedule.textInputOnly"), null);
        }
        if (active) {
            runStartGuards.checkReadiness(callerId, workflowId, steps);
        }
    }

    private static ScheduleView view(WorkflowSchedule schedule) {
        ZoneId zone = ZoneId.of(schedule.getTimeZone());
        Instant next = schedule.getNextRunAt();
        return new ScheduleView(schedule.getId(), schedule.getWorkflowId(),
                ScheduleFrequency.fromWire(schedule.getFrequency()),
                schedule.getDayOfWeek() == null ? null : DayOfWeek.of(schedule.getDayOfWeek()),
                toInt(schedule.getDayOfMonth()), schedule.getLocalTime(), schedule.getTimeZone(),
                schedule.getStatus(), schedule.getPausedAt(), next,
                next == null ? null : ScheduleClock.local(next, zone), schedule.getInput(),
                schedule.isAcknowledgeCost(), schedule.getCreatedAt(), schedule.getUpdatedAt());
    }

    private static Integer toInt(Short value) {
        return value == null ? null : value.intValue();
    }

    private ScheduleFireView fireView(ScheduleFireRow row, Instant now, Locale locale) {
        var fire = row.fire();
        Reported reported = ScheduleFireStatus.report(fire.getStatus(), fire.getErrorCode(), fire.getErrorKey(),
                args(fire.getErrorArgs()), row.runId(),
                row.runStatus() == null ? null : row.runStatus().name().toLowerCase(Locale.ROOT),
                row.runDeletedAt() != null, fire.getCreatedAt(), now);
        String message = reported.messageKey() == null ? null
                : message(reported.messageKey(), reported.messageArgs(), locale);
        return new ScheduleFireView(fire.getId(), fire.getTrigger(), fire.getScheduledAt(), reported.status(),
                reported.runId(), reported.runStatus(), reported.errorCode(), message, fire.getCreatedAt());
    }

    private String message(String key, List<Object> args, Locale locale) {
        try {
            return messages.getMessage(key, args.toArray(), locale);
        } catch (NoSuchMessageException e) {
            LOG.warn("A fire stored message key {} that no bundle holds; the generic start failure is shown", key);
            return messages.getMessage(ScheduleFireErrors.START_FAILED_KEY, new Object[0], locale);
        }
    }

    private List<Object> args(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, ARGS);
        } catch (JsonProcessingException e) {
            LOG.warn("A fire stored message arguments that are not a JSON array: {}", json, e);
            return List.of();
        }
    }
}
