package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStatus;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.domain.WorkflowVersion;
import com.flowcortex.workflow.domain.WorkflowVersionSource;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowVersionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** {@link WorkflowForkService}. */
class WorkflowForkServiceTest {

    private static final UUID ANA = UUID.randomUUID();
    private static final UUID BINH = UUID.randomUUID();

    private final WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
    private final WorkflowStepRepository stepRepository = mock(WorkflowStepRepository.class);
    private final WorkflowVersionRepository versionRepository = mock(WorkflowVersionRepository.class);
    private final StepExecutorRegistry registry = new StepExecutorRegistry(mock(MediaJobs.class), Clock.systemUTC());
    private final WorkflowForkService service = new WorkflowForkService(workflowRepository, stepRepository,
            new ServedDefinitions(stepRepository, versionRepository), registry);

    {
        when(workflowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(stepRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Workflow visible(Workflow source) {
        when(workflowRepository.findVisibleTo(source.getId(), BINH)).thenReturn(Optional.of(source));
        return source;
    }

    private static Workflow forkable(String name) {
        Workflow source = new Workflow(UUID.randomUUID(), ANA, name, true);
        source.setAllowFork(true);
        return source;
    }

    private static WorkflowStep step(Workflow workflow, int order, String name, String type, String inputRef,
            String config) {
        return new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) order, name, type, inputRef, config);
    }

    private List<WorkflowStep> savedSteps() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkflowStep>> captor = ArgumentCaptor.forClass(List.class);
        verify(stepRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void aForkIsADraftUnsharedWorkflowOfTheCallerWithTheSourceRecorded() {
        Workflow source = visible(forkable("Dub"));
        when(stepRepository.findByWorkflowIdOrderByStepOrderAsc(source.getId()))
                .thenReturn(List.of(step(source, 1, "a", "echo", null, null)));

        WorkflowView view = service.fork(BINH, source.getId());

        ArgumentCaptor<Workflow> saved = ArgumentCaptor.forClass(Workflow.class);
        verify(workflowRepository).save(saved.capture());
        Workflow fork = saved.getValue();
        assertThat(fork.getOwnerId()).isEqualTo(BINH);
        assertThat(fork.getStatus()).isEqualTo(WorkflowStatus.DRAFT);
        assertThat(fork.isShared()).isFalse();
        assertThat(fork.isAllowFork()).isFalse();
        assertThat(fork.getForkedFromId()).isEqualTo(source.getId());
        assertThat(fork.getId()).isNotEqualTo(source.getId());
        assertThat(view.id()).isEqualTo(fork.getId());
        assertThat(view.ownerId()).isEqualTo(BINH);
        assertThat(view.forkedFromId()).isEqualTo(source.getId());
        assertThat(view.steps()).hasSize(1);
    }

    @Test
    void theForkCopiesTheServedPointerVersionNotTheDraft() {
        Workflow source = visible(forkable("Dub"));
        WorkflowStep published = step(source, 1, "published", "echo", null, "{\"k\":1}");
        WorkflowVersion version = new WorkflowVersion(UUID.randomUUID(), source.getId(), 1, null,
                WorkflowVersionSource.PUBLISH, null, WorkflowSnapshots.of(source, List.of(published)).toString(), ANA,
                Instant.now());
        source.pointTo(version.getId());
        when(versionRepository.findByIdIn(Set.of(version.getId()))).thenReturn(List.of(version));
        when(stepRepository.findByWorkflowIdOrderByStepOrderAsc(source.getId()))
                .thenReturn(List.of(step(source, 1, "draft edit", "echo", null, null)));

        service.fork(BINH, source.getId());

        assertThat(savedSteps()).extracting(WorkflowStep::getName).containsExactly("published");
        assertThat(savedSteps().get(0).getConfig()).isEqualTo("{\"k\":1}");
    }

    @Test
    void theForkCopiesTheCallersPinnedStepsUnderTheLiveName() {
        Workflow source = visible(forkable("Live name"));
        WorkflowStep pinned = step(source, 1, "pinned", "echo", null, null);
        Workflow renamedLater = new Workflow(source.getId(), ANA, "Old name", true);
        WorkflowVersion version = new WorkflowVersion(UUID.randomUUID(), source.getId(), 1, null,
                WorkflowVersionSource.PUBLISH, null, WorkflowSnapshots.of(renamedLater, List.of(pinned)).toString(),
                ANA, Instant.now());
        when(versionRepository.findPinnedBy(BINH, List.of(source.getId()))).thenReturn(List.of(version));

        WorkflowView view = service.fork(BINH, source.getId());

        assertThat(savedSteps()).extracting(WorkflowStep::getName).containsExactly("pinned");
        assertThat(view.name()).isEqualTo("Live name");
    }

    @Test
    void theForkKeepsOrdersAndInputRefsWithNewStepIds() {
        Workflow source = visible(forkable("Dub"));
        WorkflowStep first = step(source, 1, "first", "echo", null, null);
        WorkflowStep second = step(source, 2, "second", "echo", first.getId().toString(), "{\"a\":true}");
        when(stepRepository.findByWorkflowIdOrderByStepOrderAsc(source.getId())).thenReturn(List.of(first, second));

        WorkflowView view = service.fork(BINH, source.getId());

        List<WorkflowStep> copies = savedSteps();
        assertThat(copies).extracting(WorkflowStep::getStepOrder).containsExactly((short) 1, (short) 2);
        assertThat(copies).extracting(WorkflowStep::getInputRef).containsExactly(null, first.getId().toString());
        assertThat(copies).extracting(WorkflowStep::getConfig).containsExactly(null, "{\"a\":true}");
        assertThat(copies).extracting(WorkflowStep::getId)
                .doesNotContain(first.getId(), second.getId()).doesNotHaveDuplicates();
        assertThat(copies).extracting(WorkflowStep::getWorkflowId).containsOnly(view.id());
    }

    @Test
    void anInvisibleSourceIsNotFoundAndWritesNothing() {
        UUID sourceId = UUID.randomUUID();
        when(workflowRepository.findVisibleTo(sourceId, BINH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.fork(BINH, sourceId)).isInstanceOf(NotFoundException.class);

        verify(workflowRepository, never()).save(any());
        verify(workflowRepository, never()).incrementForkCount(any());
        verifyNoInteractions(stepRepository);
    }

    @Test
    void theOwnerIsRefusedWithCannotForkOwn() {
        Workflow own = new Workflow(UUID.randomUUID(), BINH, "Mine", true);
        visible(own);

        assertThatThrownBy(() -> service.fork(BINH, own.getId()))
                .isInstanceOfSatisfying(UnprocessableEntityException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CANNOT_FORK_OWN"));

        verify(workflowRepository, never()).save(any());
        verify(workflowRepository, never()).incrementForkCount(any());
    }

    @Test
    void productVideoIsRefusedWith409() {
        Workflow fixed = new Workflow(ProductVideoWorkflow.ID, Workflow.SYSTEM_OWNER_ID, "Product Video", true);
        fixed.setAllowFork(true);
        visible(fixed);

        assertThatThrownBy(() -> service.fork(BINH, fixed.getId())).isInstanceOf(ConflictException.class);

        verify(workflowRepository, never()).save(any());
        verify(workflowRepository, never()).incrementForkCount(any());
    }

    @Test
    void forkingOffIsRefusedWith403AndCountsNothing() {
        Workflow closed = visible(new Workflow(UUID.randomUUID(), ANA, "Closed", true));

        assertThatThrownBy(() -> service.fork(BINH, closed.getId()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(workflowRepository, never()).save(any());
        verify(workflowRepository, never()).incrementForkCount(any());
        verify(stepRepository, never()).saveAll(any());
    }

    @Test
    void aForkIncrementsTheSourceCountOnce() {
        Workflow source = visible(forkable("Dub"));
        when(stepRepository.findByWorkflowIdOrderByStepOrderAsc(source.getId())).thenReturn(List.of());

        service.fork(BINH, source.getId());

        verify(workflowRepository).incrementForkCount(source.getId());
    }
}
