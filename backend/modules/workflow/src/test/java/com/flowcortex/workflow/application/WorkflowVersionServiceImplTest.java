package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.InvalidVersionSideException;
import com.flowcortex.workflow.api.VersionDiffView;
import com.flowcortex.workflow.api.VersionPinView;
import com.flowcortex.workflow.api.VersionSavedView;
import com.flowcortex.workflow.api.WorkflowVersionDetailView;
import com.flowcortex.workflow.api.WorkflowVersionPageView;
import com.flowcortex.workflow.api.WorkflowVersionView;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStatus;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.domain.WorkflowVersion;
import com.flowcortex.workflow.domain.WorkflowVersionSource;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowVersionPinRepository;
import com.flowcortex.workflow.infrastructure.WorkflowVersionRepository;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** {@link WorkflowVersionServiceImpl} over mocked repositories. */
class WorkflowVersionServiceImplTest {

    private static final UUID ANA = UUID.randomUUID();
    private static final UUID BEN = UUID.randomUUID();

    private final WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
    private final WorkflowStepRepository workflowStepRepository = mock(WorkflowStepRepository.class);
    private final WorkflowVersionRepository versionRepository = mock(WorkflowVersionRepository.class);
    private final WorkflowVersionPinRepository pinRepository = mock(WorkflowVersionPinRepository.class);
    private final StepExecutorRegistry registry =
            new StepExecutorRegistry(mock(MediaJobs.class), Clock.systemUTC());
    private final WorkflowVersionServiceImpl service = new WorkflowVersionServiceImpl(workflowRepository,
            workflowStepRepository, versionRepository, pinRepository,
            new WorkflowWriteAccess(workflowRepository, registry,
                    Validation.buildDefaultValidatorFactory().getValidator()),
            new VersionWriter(versionRepository, workflowRepository, workflowStepRepository), registry);

    {
        when(versionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowStepRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** A shared, published workflow of ANA that BEN can also see; the lock and visibility lookups are stubbed. */
    private Workflow sharedWorkflow() {
        Workflow workflow = new Workflow(UUID.randomUUID(), ANA, "Demo", true);
        stub(workflow);
        return workflow;
    }

    private Workflow draftWorkflow() {
        Workflow workflow = Workflow.draft(UUID.randomUUID(), ANA, "Demo", false);
        stub(workflow);
        return workflow;
    }

    private void stub(Workflow workflow) {
        when(workflowRepository.findByIdAndDeletedAtIsNull(workflow.getId())).thenReturn(Optional.of(workflow));
        when(workflowRepository.findVisibleTo(workflow.getId(), ANA)).thenReturn(Optional.of(workflow));
        if (workflow.isShared() && workflow.getStatus() == WorkflowStatus.PUBLISHED) {
            when(workflowRepository.findVisibleTo(workflow.getId(), BEN)).thenReturn(Optional.of(workflow));
        }
    }

    private List<WorkflowStep> liveSteps(Workflow workflow, String... types) {
        List<WorkflowStep> steps = new ArrayList<>();
        for (int i = 0; i < types.length; i++) {
            steps.add(new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) (i + 1), "s" + (i + 1), types[i],
                    null));
        }
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId())).thenReturn(steps);
        return steps;
    }

    private static WorkflowStep step(Workflow workflow, int order, String type, String config) {
        return new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) order, "s" + order, type, null, config);
    }

    private WorkflowVersion version(Workflow workflow, int number, WorkflowVersionSource source, boolean released,
            String name, List<WorkflowStep> steps) {
        Workflow named = new Workflow(workflow.getId(), workflow.getOwnerId(), name, true);
        WorkflowVersion version = new WorkflowVersion(UUID.randomUUID(), workflow.getId(), number, null, source,
                source == WorkflowVersionSource.ROLLBACK ? 1 : null, WorkflowSnapshots.of(named, steps).toString(),
                workflow.getOwnerId(), Instant.now());
        if (released) {
            version.release(Instant.now());
        }
        when(versionRepository.findByWorkflowIdAndVersion(workflow.getId(), number))
                .thenReturn(Optional.of(version));
        when(versionRepository.findById(version.getId())).thenReturn(Optional.of(version));
        when(versionRepository.findByIdAndWorkflowId(version.getId(), workflow.getId()))
                .thenReturn(Optional.of(version));
        if (released) {
            when(versionRepository.findByWorkflowIdAndVersionAndReleasedAtIsNotNull(workflow.getId(), number))
                    .thenReturn(Optional.of(version));
        }
        return version;
    }

    private void newest(Workflow workflow, WorkflowVersion version) {
        when(versionRepository.findTopByWorkflowIdOrderByVersionDesc(workflow.getId()))
                .thenReturn(Optional.of(version));
    }

    private static void assertStatus(Throwable e, HttpStatus status) {
        assertThat(e).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode()).isEqualTo(status));
    }

    private static void assertRefusedNaming(Throwable e, String field) {
        assertThat(e).isInstanceOfSatisfying(UnprocessableEntityException.class,
                ex -> assertThat(ex.getErrors()).containsKey(field));
    }

    @Test
    void theFirstSaveIsVersionOneWithSourceManual() {
        Workflow workflow = draftWorkflow();
        liveSteps(workflow, "echo", "echo");

        VersionSavedView saved = service.save(ANA, workflow.getId(), "first cut");

        assertThat(saved.deduplicated()).isFalse();
        WorkflowVersionView version = saved.version();
        assertThat(version.version()).isEqualTo(1);
        assertThat(version.source()).isEqualTo("manual");
        assertThat(version.changelog()).isEqualTo("first cut");
        assertThat(version.publishedBy()).isEqualTo(ANA);
        ArgumentCaptor<WorkflowVersion> row = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(versionRepository).save(row.capture());
        assertThat(WorkflowSnapshots.parse(row.getValue().getSnapshot()).path("steps")).hasSize(2);
        assertThat(workflow.getPublishedVersionId()).isNull();
    }

    @Test
    void aSaveNumbersOneAboveTheNewest() {
        Workflow workflow = draftWorkflow();
        liveSteps(workflow, "echo", "echo");
        newest(workflow, version(workflow, 3, WorkflowVersionSource.MANUAL, false, "Demo",
                List.of(step(workflow, 1, "echo", null))));

        VersionSavedView saved = service.save(ANA, workflow.getId(), null);

        assertThat(saved.version().version()).isEqualTo(4);
        assertThat(saved.version().changelog()).isNull();
        assertThat(saved.deduplicated()).isFalse();
    }

    @Test
    void anUnchangedSaveReturnsTheNewestAsDeduplicated() {
        Workflow workflow = draftWorkflow();
        List<WorkflowStep> live = liveSteps(workflow, "echo", "echo");
        newest(workflow, version(workflow, 3, WorkflowVersionSource.MANUAL, false, "Demo", live));

        VersionSavedView saved = service.save(ANA, workflow.getId(), "again");

        assertThat(saved.deduplicated()).isTrue();
        assertThat(saved.version().version()).isEqualTo(3);
        verify(versionRepository, never()).save(any());
    }

    @Test
    void aNonOwnerWhoCanSeeTheWorkflowIsForbiddenToSave() {
        Workflow workflow = sharedWorkflow();
        liveSteps(workflow, "echo");

        assertThatThrownBy(() -> service.save(BEN, workflow.getId(), null))
                .satisfies(e -> assertStatus(e, HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.hotfix(BEN, workflow.getId(), null))
                .satisfies(e -> assertStatus(e, HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.rollback(BEN, workflow.getId(), 1, null))
                .satisfies(e -> assertStatus(e, HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.rename(BEN, workflow.getId(), 1, "x"))
                .satisfies(e -> assertStatus(e, HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.setPublishedVersion(BEN, workflow.getId(), UUID.randomUUID()))
                .satisfies(e -> assertStatus(e, HttpStatus.FORBIDDEN));

        verify(versionRepository, never()).save(any());
    }

    @Test
    void aWorkflowTheCallerCannotSeeIsNotFound() {
        Workflow workflow = draftWorkflow();

        assertThatThrownBy(() -> service.save(BEN, workflow.getId(), null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.list(BEN, workflow.getId(), null, 50)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.get(BEN, workflow.getId(), 1)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.diff(BEN, workflow.getId(), null, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void renamingAPublishVersionIsRefusedNamingSource() {
        Workflow workflow = draftWorkflow();
        for (WorkflowVersionSource source : List.of(WorkflowVersionSource.PUBLISH, WorkflowVersionSource.ROLLBACK)) {
            WorkflowVersion machine = version(workflow, 1, source, true, "Demo", List.of());
            machine.rename("kept");

            assertThatThrownBy(() -> service.rename(ANA, workflow.getId(), 1, "mine"))
                    .satisfies(e -> assertRefusedNaming(e, "source"));
            assertThat(machine.getChangelog()).isEqualTo("kept");
        }
    }

    @Test
    void anEmptyChangelogClearsTheName() {
        Workflow workflow = draftWorkflow();
        WorkflowVersion hotfix = version(workflow, 2, WorkflowVersionSource.HOTFIX, false, "Demo", List.of());
        hotfix.rename("typo");

        WorkflowVersionView renamed = service.rename(ANA, workflow.getId(), 2, "");

        assertThat(renamed.changelog()).isNull();
        assertThat(hotfix.getChangelog()).isNull();
    }

    @Test
    void aRollbackReplacesNameAndStepsWithTheVersionsIdsAndAddsARollbackVersion() {
        Workflow workflow = draftWorkflow();
        liveSteps(workflow, "echo", "echo", "echo", "echo", "echo");
        WorkflowStep first = step(workflow, 1, "echo", "{\"a\":1}");
        WorkflowStep second = step(workflow, 2, "echo", null);
        WorkflowStep third = step(workflow, 3, "echo", null);
        version(workflow, 2, WorkflowVersionSource.MANUAL, false, "Old name", List.of(first, second, third));
        newest(workflow, version(workflow, 4, WorkflowVersionSource.MANUAL, false, "Demo",
                List.of(step(workflow, 1, "echo", null))));

        VersionSavedView saved = service.rollback(ANA, workflow.getId(), 2, "back to two");

        assertThat(workflow.getName()).isEqualTo("Old name");
        ArgumentCaptor<List<WorkflowStep>> restored = ArgumentCaptor.forClass(List.class);
        InOrder order = inOrder(workflowStepRepository);
        order.verify(workflowStepRepository).deleteAllByWorkflowId(workflow.getId());
        order.verify(workflowStepRepository).saveAll(restored.capture());
        assertThat(restored.getValue()).extracting(WorkflowStep::getId)
                .containsExactly(first.getId(), second.getId(), third.getId());
        assertThat(restored.getValue()).extracting(WorkflowStep::getConfig).containsExactly("{\"a\":1}", null, null);
        assertThat(saved.deduplicated()).isFalse();
        assertThat(saved.version().version()).isEqualTo(5);
        assertThat(saved.version().source()).isEqualTo("rollback");
        assertThat(saved.version().restoredFromVersion()).isEqualTo(2);
        assertThat(saved.version().changelog()).isEqualTo("back to two");
        assertThat(workflow.getPublishedVersionId()).isNull();
    }

    @Test
    void aRollbackToAVersionWithAnUnknownTypeIsRefusedAndChangesNothing() {
        Workflow workflow = draftWorkflow();
        liveSteps(workflow, "echo");
        version(workflow, 1, WorkflowVersionSource.MANUAL, false, "Old name",
                List.of(step(workflow, 1, "echo", null), step(workflow, 2, "no-such-type", null)));

        assertThatThrownBy(() -> service.rollback(ANA, workflow.getId(), 1, null))
                .isInstanceOfSatisfying(ConstraintViolationException.class, e -> assertThat(e.getConstraintViolations())
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactly("steps[1].type"));

        assertThat(workflow.getName()).isEqualTo("Demo");
        verify(workflowStepRepository, never()).deleteAllByWorkflowId(any());
        verify(workflowStepRepository, never()).saveAll(any());
        verify(versionRepository, never()).save(any());
    }

    @Test
    void aRollbackToAMissingVersionIsRefusedNamingVersion() {
        Workflow workflow = draftWorkflow();
        liveSteps(workflow, "echo");

        assertThatThrownBy(() -> service.rollback(ANA, workflow.getId(), 9, null))
                .satisfies(e -> assertRefusedNaming(e, "version"));

        verify(workflowStepRepository, never()).deleteAllByWorkflowId(any());
    }

    @Test
    void aHotfixLeavesThePointerWhereItWas() {
        Workflow workflow = sharedWorkflow();
        liveSteps(workflow, "echo", "echo");
        WorkflowVersion pointed = version(workflow, 2, WorkflowVersionSource.PUBLISH, true, "Demo",
                List.of(step(workflow, 1, "echo", null)));
        workflow.pointTo(pointed.getId());
        newest(workflow, pointed);

        VersionSavedView saved = service.hotfix(ANA, workflow.getId(), "typo in prompt");

        assertThat(saved.version().version()).isEqualTo(3);
        assertThat(saved.version().source()).isEqualTo("hotfix");
        assertThat(saved.version().changelog()).isEqualTo("typo in prompt");
        assertThat(saved.version().releasedAt()).isNull();
        assertThat(workflow.getPublishedVersionId()).isEqualTo(pointed.getId());
    }

    @Test
    void movingThePointerToAnotherWorkflowsVersionIsRefusedNamingVersionId() {
        Workflow workflow = sharedWorkflow();
        UUID foreign = UUID.randomUUID();
        when(versionRepository.findByIdAndWorkflowId(foreign, workflow.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setPublishedVersion(ANA, workflow.getId(), foreign))
                .satisfies(e -> assertRefusedNaming(e, "versionId"));

        assertThat(workflow.getPublishedVersionId()).isNull();
    }

    @Test
    void aMissingVersionIdIsRefused() {
        Workflow workflow = sharedWorkflow();

        assertThatThrownBy(() -> service.setPublishedVersion(ANA, workflow.getId(), null))
                .satisfies(e -> assertRefusedNaming(e, "versionId"));
    }

    @Test
    void movingThePointerReleasesTheVersion() {
        Workflow workflow = sharedWorkflow();
        liveSteps(workflow, "echo");
        WorkflowVersion target = version(workflow, 4, WorkflowVersionSource.HOTFIX, false, "Demo", List.of());
        assertThat(target.isReleased()).isFalse();

        WorkflowView answered = service.setPublishedVersion(ANA, workflow.getId(), target.getId());

        assertThat(target.isReleased()).isTrue();
        assertThat(workflow.getPublishedVersionId()).isEqualTo(target.getId());
        assertThat(answered.publishedVersionId()).isEqualTo(target.getId());
        assertThat(answered.servedVersion()).isNull();
    }

    @Test
    void aSkeletonReaderAskingForTheDraftSideIsForbidden() {
        Workflow workflow = sharedWorkflow();
        liveSteps(workflow, "echo");

        assertThatThrownBy(() -> service.diff(BEN, workflow.getId(), "published", "draft"))
                .satisfies(e -> assertStatus(e, HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.diff(BEN, workflow.getId(), null, null))
                .satisfies(e -> assertStatus(e, HttpStatus.FORBIDDEN));
    }

    @Test
    void aSkeletonDiffCarriesChangedFieldNamesWithoutValues() {
        Workflow workflow = sharedWorkflow();
        WorkflowStep before = step(workflow, 1, "echo", "{\"prompt\":\"one\"}");
        WorkflowStep after = new WorkflowStep(before.getId(), workflow.getId(), (short) 1, "s1", "echo", null,
                "{\"prompt\":\"two\"}");
        version(workflow, 1, WorkflowVersionSource.PUBLISH, true, "Demo", List.of(before));
        version(workflow, 2, WorkflowVersionSource.PUBLISH, true, "Demo", List.of(after));

        VersionDiffView seenByBen = service.diff(BEN, workflow.getId(), "1", "2");
        VersionDiffView seenByAna = service.diff(ANA, workflow.getId(), "1", "2");

        assertThat(seenByBen.skeleton()).isTrue();
        assertThat(seenByBen.steps()).singleElement().satisfies(change -> {
            assertThat(change.status()).isEqualTo("modified");
            assertThat(change.changedFields()).containsExactly("config");
            assertThat(change.before()).isNull();
            assertThat(change.after()).isNull();
        });
        assertThat(seenByAna.skeleton()).isFalse();
        assertThat(seenByAna.steps().getFirst().after()).containsKey("config");
    }

    @Test
    void aNonOwnerDiffAgainstAnUnreleasedVersionIsNotFound() {
        Workflow workflow = sharedWorkflow();
        version(workflow, 2, WorkflowVersionSource.PUBLISH, true, "Demo", List.of());
        version(workflow, 4, WorkflowVersionSource.HOTFIX, false, "Demo", List.of());

        assertThatThrownBy(() -> service.diff(BEN, workflow.getId(), "2", "4")).isInstanceOf(NotFoundException.class);
        verify(versionRepository, never()).findByWorkflowIdAndVersion(any(), anyInt());
    }

    @Test
    void aSideThatIsNeitherDraftPublishedNorANumberIsRefusedNamingIt() {
        Workflow workflow = draftWorkflow();

        assertThatThrownBy(() -> service.diff(ANA, workflow.getId(), "latest", null))
                .isInstanceOfSatisfying(InvalidVersionSideException.class, e -> assertThat(e.field()).isEqualTo("source"));
        assertThatThrownBy(() -> service.diff(ANA, workflow.getId(), null, "0"))
                .isInstanceOfSatisfying(InvalidVersionSideException.class, e -> assertThat(e.field()).isEqualTo("target"));
    }

    @Test
    void anOwnerWhoseWorkflowHasNoVersionYetGetsNotFoundForThePublishedSide() {
        Workflow workflow = draftWorkflow();
        liveSteps(workflow, "echo");

        assertThatThrownBy(() -> service.diff(ANA, workflow.getId(), null, null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void pinningAMissingVersionIsRefusedNamingVersion() {
        Workflow workflow = sharedWorkflow();

        assertThatThrownBy(() -> service.pin(BEN, workflow.getId(), 7, null))
                .satisfies(e -> assertRefusedNaming(e, "version"));

        verify(pinRepository, never()).upsert(any(), any(), anyInt(), any(), any());
    }

    @Test
    void pinningAWorkflowTheCallerCannotSeeIsNotFound() {
        Workflow workflow = draftWorkflow();

        assertThatThrownBy(() -> service.pin(BEN, workflow.getId(), 1, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getPin(BEN, workflow.getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.unpin(BEN, workflow.getId())).isInstanceOf(NotFoundException.class);

        verify(pinRepository, never()).upsert(any(), any(), anyInt(), any(), any());
    }

    @Test
    void aSecondPinReplacesTheFirst() {
        Workflow workflow = sharedWorkflow();
        version(workflow, 1, WorkflowVersionSource.PUBLISH, true, "Demo", List.of());
        version(workflow, 2, WorkflowVersionSource.PUBLISH, true, "Demo", List.of());

        service.pin(BEN, workflow.getId(), 1, "stable for my batch");
        VersionPinView second = service.pin(BEN, workflow.getId(), 2, "");

        InOrder order = inOrder(pinRepository);
        order.verify(pinRepository).upsert(eq(BEN), eq(workflow.getId()), eq(1), eq("stable for my batch"), any());
        order.verify(pinRepository).upsert(eq(BEN), eq(workflow.getId()), eq(2), eq(null), any());
        assertThat(second.pinnedVersion()).isEqualTo(2);
        assertThat(second.reason()).isNull();
    }

    @Test
    void anOwnersPinIsRefusedNamingVersion() {
        Workflow workflow = sharedWorkflow();
        version(workflow, 1, WorkflowVersionSource.PUBLISH, true, "Demo", List.of());

        assertThatThrownBy(() -> service.pin(ANA, workflow.getId(), 1, null))
                .satisfies(e -> assertRefusedNaming(e, "version"));

        verify(pinRepository, never()).upsert(any(), any(), anyInt(), any(), any());
    }

    @Test
    void pinningAnUnreleasedVersionIsRefusedNamingVersion() {
        Workflow workflow = sharedWorkflow();
        version(workflow, 3, WorkflowVersionSource.MANUAL, false, "Demo", List.of());

        assertThatThrownBy(() -> service.pin(BEN, workflow.getId(), 3, null))
                .satisfies(e -> assertRefusedNaming(e, "version"));

        verify(pinRepository, never()).upsert(any(), any(), anyInt(), any(), any());
    }

    @Test
    void aNonOwnerReadingAnUnreleasedVersionIsNotFound() {
        Workflow workflow = sharedWorkflow();
        version(workflow, 4, WorkflowVersionSource.HOTFIX, false, "Demo", List.of());

        assertThatThrownBy(() -> service.get(BEN, workflow.getId(), 4)).isInstanceOf(NotFoundException.class);
        verify(versionRepository, never()).findByWorkflowIdAndVersion(any(), anyInt());
    }

    @Test
    void aNonOwnerReadsAReleasedVersionAsASkeleton() {
        Workflow workflow = sharedWorkflow();
        version(workflow, 2, WorkflowVersionSource.PUBLISH, true, "Demo",
                List.of(step(workflow, 1, "echo", "{\"prompt\":\"secret\"}")));

        WorkflowVersionDetailView seenByBen = service.get(BEN, workflow.getId(), 2);
        WorkflowVersionDetailView seenByAna = service.get(ANA, workflow.getId(), 2);

        assertThat(seenByBen.snapshot().skeleton()).isTrue();
        assertThat(seenByBen.snapshot().steps()).singleElement().satisfies(step -> {
            assertThat(step.config()).isNull();
            assertThat(step.inputRef()).isNull();
            assertThat(step.type()).isEqualTo("echo");
        });
        assertThat(seenByAna.snapshot().skeleton()).isFalse();
        assertThat(seenByAna.snapshot().steps().getFirst().config()).isNotNull();
    }

    @Test
    void theNonOwnerListOmitsUnreleasedVersions() {
        Workflow workflow = sharedWorkflow();
        WorkflowVersion hotfix = version(workflow, 4, WorkflowVersionSource.HOTFIX, false, "Demo", List.of());
        WorkflowVersion published = version(workflow, 2, WorkflowVersionSource.PUBLISH, true, "Demo", List.of());
        when(versionRepository.findByWorkflowIdOrderByVersionDesc(eq(workflow.getId()), any()))
                .thenReturn(List.of(hotfix, published));
        when(versionRepository.findByWorkflowIdAndReleasedAtIsNotNullOrderByVersionDesc(eq(workflow.getId()), any()))
                .thenReturn(List.of(published));

        WorkflowVersionPageView seenByBen = service.list(BEN, workflow.getId(), null, 50);
        WorkflowVersionPageView seenByAna = service.list(ANA, workflow.getId(), null, 50);

        assertThat(seenByBen.items()).extracting(WorkflowVersionView::version).containsExactly(2);
        assertThat(seenByAna.items()).extracting(WorkflowVersionView::version).containsExactly(4, 2);
        verify(versionRepository, times(1)).findByWorkflowIdOrderByVersionDesc(eq(workflow.getId()), any());
    }

    @Test
    void aFullPageSaysWhichVersionFetchesTheNextOne() {
        Workflow workflow = draftWorkflow();
        List<WorkflowVersion> three = List.of(
                version(workflow, 3, WorkflowVersionSource.MANUAL, false, "Demo", List.of()),
                version(workflow, 2, WorkflowVersionSource.MANUAL, false, "Demo", List.of()),
                version(workflow, 1, WorkflowVersionSource.MANUAL, false, "Demo", List.of()));
        when(versionRepository.findByWorkflowIdOrderByVersionDesc(eq(workflow.getId()), any())).thenReturn(three);

        WorkflowVersionPageView page = service.list(ANA, workflow.getId(), null, 2);

        assertThat(page.items()).extracting(WorkflowVersionView::version).containsExactly(3, 2);
        assertThat(page.nextBeforeVersion()).isEqualTo(2);
    }
}
