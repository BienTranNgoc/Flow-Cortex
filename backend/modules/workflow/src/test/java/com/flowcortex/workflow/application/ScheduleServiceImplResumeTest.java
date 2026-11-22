package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.RequestLocale;
import com.flowcortex.workflow.api.schedule.ScheduleView;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowSchedule;
import com.flowcortex.workflow.infrastructure.ScheduleFireRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowScheduleRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;

/**
 * Resume writes the next fire of the rule it stored, whichever rule that turns out to be: a replace that landed
 * between its read and its write is met by a second computation, never by a stale instant.
 */
class ScheduleServiceImplResumeTest {

    private static final String ZONE = "Asia/Ho_Chi_Minh";
    private static final Instant NOW = Instant.parse("2026-11-24T00:00:00Z");

    private final UUID caller = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();
    private final WorkflowScheduleRepository schedules = mock(WorkflowScheduleRepository.class);
    private final WorkflowRepository workflows = mock(WorkflowRepository.class);
    private final ServedDefinitions servedDefinitions = mock(ServedDefinitions.class);
    private final RunStartGuards guards = mock(RunStartGuards.class);
    private final ScheduleServiceImpl service = new ScheduleServiceImpl(schedules, mock(ScheduleFireRepository.class),
            mock(ScheduleFires.class), workflows, servedDefinitions, mock(StepExecutorRegistry.class), guards,
            mock(MessageSource.class), mock(RequestLocale.class), Clock.fixed(NOW, ZoneOffset.UTC), true);

    @Test
    void anUntouchedRuleIsWrittenOnTheFirstAttempt() {
        stored(row("daily", null, null, "08:00", false), row("daily", null, null, "08:00", true));
        visible();
        when(resume("daily", null, null, "08:00")).thenReturn(1);

        ScheduleView view = service.resume(caller, workflowId);

        assertThat(view.status()).isEqualTo(WorkflowSchedule.ACTIVE);
        verify(schedules, times(1)).resume(any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(writtenNextRuns()).containsExactly(next("daily", null, null, "08:00"));
    }

    @Test
    void aRuleReplacedAfterTheReadStoresTheNextFireOfTheNewRule() {
        stored(row("daily", null, null, "08:00", false), row("weekly", (short) 1, null, "09:00", false),
                row("weekly", (short) 1, null, "09:00", true));
        visible();
        when(resume("daily", null, null, "08:00")).thenReturn(0);
        when(resume("weekly", (short) 1, null, "09:00")).thenReturn(1);

        ScheduleView view = service.resume(caller, workflowId);

        assertThat(view.status()).isEqualTo(WorkflowSchedule.ACTIVE);
        Instant fresh = next("weekly", (short) 1, null, "09:00");
        assertThat(fresh).as("the new rule fires at another instant").isNotEqualTo(next("daily", null, null, "08:00"));
        assertThat(writtenNextRuns()).containsExactly(next("daily", null, null, "08:00"), fresh);
        verify(workflows, times(1)).findVisibleTo(workflowId, caller);
        verify(guards, times(1)).checkReadiness(eq(caller), eq(workflowId), any());
    }

    @Test
    void aRuleThatKeepsChangingIsTriedThreeTimesThenTheCurrentRowIsAnswered() {
        stored(row("daily", null, null, "08:00", false), row("daily", null, null, "09:00", false),
                row("daily", null, null, "10:00", false), row("daily", null, null, "11:00", false));
        visible();
        when(schedules.resume(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0);

        ScheduleView view = service.resume(caller, workflowId);

        verify(schedules, times(3)).resume(any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(view.status()).isEqualTo(WorkflowSchedule.PAUSED);
        assertThat(view.time()).isEqualTo(LocalTime.of(11, 0));
    }

    @Test
    void aConcurrentResumeThatWonIsAnsweredUnchanged() {
        stored(row("daily", null, null, "08:00", false), row("daily", null, null, "08:00", true));
        visible();
        when(resume("daily", null, null, "08:00")).thenReturn(0);

        ScheduleView view = service.resume(caller, workflowId);

        assertThat(view.status()).isEqualTo(WorkflowSchedule.ACTIVE);
        verify(schedules, times(1)).resume(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aScheduleDeletedBeforeTheWriteIs404() {
        WorkflowSchedule paused = row("daily", null, null, "08:00", false);
        when(schedules.findByOwnerIdAndWorkflowId(caller, workflowId)).thenReturn(Optional.of(paused),
                Optional.empty());
        visible();
        when(resume("daily", null, null, "08:00")).thenReturn(0);

        assertThatThrownBy(() -> service.resume(caller, workflowId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void anActiveScheduleIsAnsweredWithoutAnyCheckOrWrite() {
        stored(row("daily", null, null, "08:00", true));

        assertThat(service.resume(caller, workflowId).status()).isEqualTo(WorkflowSchedule.ACTIVE);

        verify(schedules, never()).resume(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(workflows, never()).findVisibleTo(any(), any());
    }

    private void stored(WorkflowSchedule first, WorkflowSchedule... later) {
        var reads = when(schedules.findByOwnerIdAndWorkflowId(caller, workflowId)).thenReturn(Optional.of(first));
        for (WorkflowSchedule read : later) {
            reads = reads.thenReturn(Optional.of(read));
        }
    }

    private void visible() {
        when(workflows.findVisibleTo(workflowId, caller)).thenReturn(Optional.of(mock(Workflow.class)));
    }

    private int resume(String frequency, Short dayOfWeek, Short dayOfMonth, String time) {
        return schedules.resume(eq(caller), eq(workflowId), eq(frequency),
                dayOfWeek == null ? isNull() : eq(dayOfWeek), dayOfMonth == null ? isNull() : eq(dayOfMonth),
                eq(LocalTime.parse(time)), eq(ZONE), any(Instant.class), eq(NOW));
    }

    private List<Instant> writtenNextRuns() {
        ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
        verify(schedules, atLeastOnce()).resume(any(), any(), any(), any(), any(), any(), any(),
                next.capture(), any());
        return next.getAllValues();
    }

    private static Instant next(String frequency, Short dayOfWeek, Short dayOfMonth, String time) {
        return ScheduleClock.next(frequency, dayOfWeek == null ? null : dayOfWeek.intValue(),
                dayOfMonth == null ? null : dayOfMonth.intValue(), LocalTime.parse(time), ZoneId.of(ZONE), NOW);
    }

    private static WorkflowSchedule row(String frequency, Short dayOfWeek, Short dayOfMonth, String time,
            boolean active) {
        WorkflowSchedule row = mock(WorkflowSchedule.class);
        when(row.getFrequency()).thenReturn(frequency);
        when(row.getDayOfWeek()).thenReturn(dayOfWeek);
        when(row.getDayOfMonth()).thenReturn(dayOfMonth);
        when(row.getLocalTime()).thenReturn(LocalTime.parse(time));
        when(row.getTimeZone()).thenReturn(ZONE);
        when(row.isActive()).thenReturn(active);
        when(row.getStatus()).thenReturn(active ? WorkflowSchedule.ACTIVE : WorkflowSchedule.PAUSED);
        return row;
    }
}
