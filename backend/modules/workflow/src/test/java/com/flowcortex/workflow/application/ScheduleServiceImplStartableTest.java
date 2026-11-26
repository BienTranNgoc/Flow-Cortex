package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.shared.web.RequestLocale;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.schedule.ScheduleView;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowSchedule;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.ScheduleFireRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowScheduleRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.MessageSource;

/**
 * A schedule is refused for exactly the first steps the start path asks an upload for: a fire has none to give.
 * The judgement is made by the real step registry, so it follows what a step type binds, not how it executes.
 */
class ScheduleServiceImplStartableTest {

    private static final Instant NOW = Instant.parse("2026-11-24T00:00:00Z");

    private final UUID caller = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();
    private final WorkflowScheduleRepository schedules = mock(WorkflowScheduleRepository.class);
    private final WorkflowRepository workflows = mock(WorkflowRepository.class);
    private final ServedDefinitions servedDefinitions = mock(ServedDefinitions.class);
    private final RunStartGuards guards = mock(RunStartGuards.class);
    private final ScheduleServiceImpl service = new ScheduleServiceImpl(schedules, mock(ScheduleFireRepository.class),
            mock(ScheduleFires.class), workflows, servedDefinitions,
            new StepExecutorRegistry(mock(MediaJobs.class), Clock.systemUTC()),
            guards, mock(MessageSource.class), mock(RequestLocale.class), Clock.fixed(NOW, ZoneOffset.UTC), true);

    @ParameterizedTest
    @ValueSource(strings = {"fetch_video", "generate_speech", "echo"})
    void aFirstStepThatTakesTextIsAcceptedOnResume(String firstStepType) {
        pausedScheduleOnWorkflowStartingWith(firstStepType);

        ScheduleView view = service.resume(caller, workflowId);

        assertThat(view.status()).isEqualTo(WorkflowSchedule.ACTIVE);
        verify(guards).checkReadiness(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ingest_video", "ingest_image", "generate_script", "generate_video", "probe"})
    void aFirstStepThatTakesAnUploadIsRefusedOnResume(String firstStepType) {
        pausedScheduleOnWorkflowStartingWith(firstStepType);

        assertThatThrownBy(() -> service.resume(caller, workflowId)).isInstanceOf(UnprocessableEntityException.class);

        verify(guards, never()).checkReadiness(any(), any(), any());
    }

    private void pausedScheduleOnWorkflowStartingWith(String firstStepType) {
        WorkflowSchedule paused = row(false);
        WorkflowSchedule active = row(true);
        when(schedules.findByOwnerIdAndWorkflowId(caller, workflowId)).thenReturn(Optional.of(paused),
                Optional.of(active));
        Workflow workflow = mock(Workflow.class);
        when(workflows.findVisibleTo(workflowId, caller)).thenReturn(Optional.of(workflow));
        WorkflowStep first = mock(WorkflowStep.class);
        when(first.getType()).thenReturn(firstStepType);
        when(servedDefinitions.stepsFor(workflow, caller)).thenReturn(List.of(first));
        when(schedules.resume(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    }

    private static WorkflowSchedule row(boolean active) {
        WorkflowSchedule row = mock(WorkflowSchedule.class);
        when(row.getFrequency()).thenReturn("daily");
        when(row.getDayOfWeek()).thenReturn(null);
        when(row.getDayOfMonth()).thenReturn(null);
        when(row.getLocalTime()).thenReturn(LocalTime.of(8, 0));
        when(row.getTimeZone()).thenReturn("Asia/Ho_Chi_Minh");
        when(row.isActive()).thenReturn(active);
        when(row.getStatus()).thenReturn(active ? WorkflowSchedule.ACTIVE : WorkflowSchedule.PAUSED);
        return row;
    }
}
