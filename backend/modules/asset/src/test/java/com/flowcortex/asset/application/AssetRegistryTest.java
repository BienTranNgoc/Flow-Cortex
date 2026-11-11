package com.flowcortex.asset.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.asset.domain.NewSpaceAsset;
import com.flowcortex.asset.domain.RegistrationRow;
import com.flowcortex.asset.domain.RegistrationStatus;
import com.flowcortex.asset.domain.SourceType;
import com.flowcortex.asset.infrastructure.AssetRegistrationStore;
import com.flowcortex.asset.infrastructure.SpaceAssetStore;
import com.flowcortex.media.api.BoundRunInput;
import com.flowcortex.media.api.MediaStoreUnavailableException;
import com.flowcortex.media.api.RegistrableMedia;
import com.flowcortex.media.api.StoredMediaFile;
import com.flowcortex.space.api.PersonalSpaces;
import com.flowcortex.workflow.api.RunRecord;
import com.flowcortex.workflow.api.RunRecordStep;
import com.flowcortex.workflow.api.RunRecords;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AssetRegistryTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID SPACE = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID WORKFLOW = UUID.randomUUID();
    private static final String BUCKET = "media";

    private final RunRecords runs = mock(RunRecords.class);
    private final RegistrableMedia media = mock(RegistrableMedia.class);
    private final PersonalSpaces spaces = mock(PersonalSpaces.class);
    private final AssetRegistrationStore queue = mock(AssetRegistrationStore.class);
    private final SpaceAssetStore assets = mock(SpaceAssetStore.class);
    private final AssetRegistrationPool pool = mock(AssetRegistrationPool.class);
    private final AssetRegistry registry = new AssetRegistry(runs, media, spaces, queue, assets, pool);

    AssetRegistryTest() {
        when(spaces.personalSpaceOf(OWNER)).thenReturn(SPACE);
    }

    @Test
    void completedRunRegistersTheFinalStepOutputsWithNumberedTitles() {
        RunRecordStep first = new RunRecordStep(UUID.randomUUID(), 1, "generate", (short) 1);
        RunRecordStep last = new RunRecordStep(UUID.randomUUID(), 2, "upscale", (short) 2);
        givenRun("completed", List.of(first, last));
        when(media.stepOutputs(first.runStepId(), (short) 1)).thenReturn(List.of(output(OWNER, "draft.png", "image/png")));
        when(media.stepOutputs(last.runStepId(), (short) 2)).thenReturn(List.of(
                output(OWNER, "b.mp4", "video/mp4"), output(OWNER, "a.png", "image/png")));
        when(assets.runTitleVersion(RUN)).thenReturn(OptionalInt.empty());
        when(assets.maxRunTitleVersion(eq(SPACE), anyString(), eq(RUN))).thenReturn(1);
        when(assets.insertIfAbsent(any())).thenReturn(true);

        assertThat(registry.process(row(SourceType.RUN_OUTPUTS))).isFalse();

        ArgumentCaptor<NewSpaceAsset> inserted = ArgumentCaptor.forClass(NewSpaceAsset.class);
        verify(assets, times(2)).insertIfAbsent(inserted.capture());
        assertThat(inserted.getAllValues()).extracting(NewSpaceAsset::objectKey).containsExactly("a.png", "b.mp4");
        assertThat(inserted.getAllValues()).extracting(NewSpaceAsset::kind).containsExactly("image", "video");
        assertThat(inserted.getAllValues()).allSatisfy(asset -> {
            assertThat(asset.spaceId()).isEqualTo(SPACE);
            assertThat(asset.source()).isEqualTo("run");
            assertThat(asset.titleVersion()).isEqualTo(2);
            assertThat(asset.runStepId()).isEqualTo(last.runStepId());
        });
        verify(queue).finish(any(), eq(RegistrationStatus.DONE));
    }

    @Test
    void aRetriedRunKeepsTheVersionItAlreadyWrote() {
        RunRecordStep step = new RunRecordStep(UUID.randomUUID(), 1, "generate", (short) 1);
        givenRun("completed", List.of(step));
        when(media.stepOutputs(step.runStepId(), (short) 1)).thenReturn(List.of(output(OWNER, "a.png", "image/png")));
        when(assets.runTitleVersion(RUN)).thenReturn(OptionalInt.of(3));
        when(assets.insertIfAbsent(any())).thenReturn(false);

        registry.process(row(SourceType.RUN_OUTPUTS));

        ArgumentCaptor<NewSpaceAsset> inserted = ArgumentCaptor.forClass(NewSpaceAsset.class);
        verify(assets).insertIfAbsent(inserted.capture());
        assertThat(inserted.getValue().titleVersion()).isEqualTo(3);
        verify(assets, never()).maxRunTitleVersion(any(), anyString(), any());
        verify(queue).finish(any(), eq(RegistrationStatus.DONE));
    }

    @Test
    void outputsOfAnotherOwnerOrUnsupportedKindAreSkipped() {
        RunRecordStep step = new RunRecordStep(UUID.randomUUID(), 1, "generate", (short) 1);
        givenRun("completed", List.of(step));
        when(media.stepOutputs(step.runStepId(), (short) 1)).thenReturn(List.of(
                output(UUID.randomUUID(), "a.png", "image/png"), output(OWNER, "b.txt", "text/plain")));
        when(assets.runTitleVersion(RUN)).thenReturn(OptionalInt.of(1));

        registry.process(row(SourceType.RUN_OUTPUTS));

        verify(assets, never()).insertIfAbsent(any());
        verify(queue).finish(any(), eq(RegistrationStatus.DONE));
    }

    @Test
    void aMissingRunSkipsTheWholeSource() {
        when(runs.find(RUN)).thenReturn(Optional.empty());

        registry.process(row(SourceType.RUN_OUTPUTS));
        registry.process(row(SourceType.RUN_INPUTS));

        verify(queue, times(2)).finish(any(), eq(RegistrationStatus.SKIPPED));
    }

    @Test
    void anUnfinishedRunSkipsTheWholeSource() {
        givenRun("failed", List.of());

        registry.process(row(SourceType.RUN_OUTPUTS));

        verify(queue).finish(any(), eq(RegistrationStatus.SKIPPED));
        verify(media, never()).stepOutputs(any(), anyShort());
    }

    @Test
    void anUnavailableStoreRetriesTheRowAndTellsTheCaller() {
        RunRecordStep step = new RunRecordStep(UUID.randomUUID(), 1, "generate", (short) 1);
        givenRun("completed", List.of(step));
        when(media.stepOutputs(step.runStepId(), (short) 1))
                .thenThrow(new MediaStoreUnavailableException("down", null));

        assertThat(registry.process(row(SourceType.RUN_OUTPUTS))).isTrue();

        verify(queue).retry(any(), eq(AssetRegistry.STORE_UNAVAILABLE));
    }

    @Test
    void aFailedInsertRetriesTheRow() {
        RunRecordStep step = new RunRecordStep(UUID.randomUUID(), 1, "generate", (short) 1);
        givenRun("completed", List.of(step));
        when(media.stepOutputs(step.runStepId(), (short) 1)).thenReturn(List.of(output(OWNER, "a.png", "image/png")));
        when(assets.runTitleVersion(RUN)).thenReturn(OptionalInt.of(1));
        when(assets.insertIfAbsent(any())).thenThrow(new DataAccessResourceFailureException("db"));

        assertThat(registry.process(row(SourceType.RUN_OUTPUTS))).isFalse();

        verify(queue).retry(any(), eq(AssetRegistry.INSERT_FAILED));
    }

    @Test
    void anUnexpectedFailureRetriesTheRow() {
        when(runs.find(RUN)).thenThrow(new IllegalStateException("boom"));

        registry.process(row(SourceType.RUN_OUTPUTS));

        verify(queue).retry(any(), eq(AssetRegistry.UNEXPECTED));
    }

    @Test
    void aFailedFinishNeverEscapes() {
        when(runs.find(RUN)).thenReturn(Optional.empty());
        when(queue.finish(any(), any())).thenThrow(new DataAccessResourceFailureException("db"));

        assertThatCode(() -> registry.process(row(SourceType.RUN_OUTPUTS))).doesNotThrowAnyException();
    }

    @Test
    void boundInputsAreKeptAndRegisteredAsUploads() {
        givenRun("running", List.of());
        BoundRunInput input = input(OWNER, "video", "in.mp4");
        when(media.boundInputs(RUN)).thenReturn(List.of(input));
        when(media.keepRunInput(BUCKET, "in.mp4")).thenReturn(OptionalLong.of(42));
        when(assets.insertIfAbsent(any())).thenReturn(true);

        registry.process(row(SourceType.RUN_INPUTS));

        ArgumentCaptor<NewSpaceAsset> inserted = ArgumentCaptor.forClass(NewSpaceAsset.class);
        verify(assets).insertIfAbsent(inserted.capture());
        assertThat(inserted.getValue().source()).isEqualTo("upload");
        assertThat(inserted.getValue().bytes()).isEqualTo(42);
        assertThat(inserted.getValue().runStepId()).isNull();
        verify(queue).finish(any(), eq(RegistrationStatus.DONE));
    }

    @Test
    void aRunWithoutBoundInputsIsDone() {
        givenRun("running", List.of());
        when(media.boundInputs(RUN)).thenReturn(List.of());

        registry.process(row(SourceType.RUN_INPUTS));

        verify(spaces, never()).personalSpaceOf(any());
        verify(queue).finish(any(), eq(RegistrationStatus.DONE));
    }

    @Test
    void inputsOfAnotherOwnerUnsupportedKindOrMissingObjectAreSkipped() {
        givenRun("running", List.of());
        when(media.boundInputs(RUN)).thenReturn(List.of(
                input(UUID.randomUUID(), "video", "a.mp4"),
                input(OWNER, "document", "b.pdf"),
                input(OWNER, "image", "c.png")));
        when(media.keepRunInput(BUCKET, "c.png")).thenReturn(OptionalLong.empty());

        registry.process(row(SourceType.RUN_INPUTS));

        verify(media, never()).keepRunInput(BUCKET, "a.mp4");
        verify(media, never()).keepRunInput(BUCKET, "b.pdf");
        verify(assets, never()).insertIfAbsent(any());
        verify(queue).finish(any(), eq(RegistrationStatus.DONE));
    }

    @Test
    void anAlreadyRegisteredInputReleasesAStrayCopy() {
        givenRun("running", List.of());
        BoundRunInput input = input(OWNER, "image", "c.png");
        when(media.boundInputs(RUN)).thenReturn(List.of(input));
        when(assets.existsByMediaAssetId(input.mediaAssetId())).thenReturn(true);
        when(assets.pointsAtObject(BUCKET, "c.png")).thenReturn(false);

        registry.process(row(SourceType.RUN_INPUTS));

        verify(media).releaseRunInput(BUCKET, "c.png");
        verify(media, never()).keepRunInput(any(), any());
        verify(queue).finish(any(), eq(RegistrationStatus.DONE));
    }

    @Test
    void theCopyAnAssetPointsAtStaysKept() {
        givenRun("running", List.of());
        BoundRunInput input = input(OWNER, "image", "c.png");
        when(media.boundInputs(RUN)).thenReturn(List.of(input));
        when(media.keepRunInput(BUCKET, "c.png")).thenReturn(OptionalLong.of(7));
        when(assets.insertIfAbsent(any())).thenReturn(false);
        when(assets.pointsAtObject(BUCKET, "c.png")).thenReturn(true);

        registry.process(row(SourceType.RUN_INPUTS));

        verify(media, never()).releaseRunInput(any(), any());
        verify(queue).finish(any(), eq(RegistrationStatus.DONE));
    }

    @Test
    void anUnavailableStoreWhileReleasingStopsAndRetries() {
        givenRun("running", List.of());
        BoundRunInput first = input(OWNER, "image", "c.png");
        BoundRunInput second = input(OWNER, "image", "d.png");
        when(media.boundInputs(RUN)).thenReturn(List.of(first, second));
        when(assets.existsByMediaAssetId(first.mediaAssetId())).thenReturn(true);
        doThrow(new MediaStoreUnavailableException("down", null)).when(media).releaseRunInput(BUCKET, "c.png");

        assertThat(registry.process(row(SourceType.RUN_INPUTS))).isTrue();

        verify(media, never()).keepRunInput(BUCKET, "d.png");
        verify(queue).retry(any(), eq(AssetRegistry.STORE_UNAVAILABLE));
    }

    @Test
    void anUnavailableStoreWhileKeepingStopsAndRetries() {
        givenRun("running", List.of());
        BoundRunInput input = input(OWNER, "image", "c.png");
        when(media.boundInputs(RUN)).thenReturn(List.of(input));
        when(media.keepRunInput(BUCKET, "c.png")).thenThrow(new MediaStoreUnavailableException("down", null));

        assertThat(registry.process(row(SourceType.RUN_INPUTS))).isTrue();

        verify(assets, never()).insertIfAbsent(any());
        verify(queue).retry(any(), eq(AssetRegistry.STORE_UNAVAILABLE));
    }

    @Test
    void announceOutsideATransactionHandsOffAtOnce() {
        doAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return null;
        }).when(pool).execute(any());
        when(queue.claim(SourceType.RUN_OUTPUTS, RUN)).thenReturn(Optional.of(row(SourceType.RUN_OUTPUTS)));
        when(runs.find(RUN)).thenReturn(Optional.empty());

        registry.announce(SourceType.RUN_OUTPUTS, RUN, OWNER);

        verify(queue).upsertPending(SourceType.RUN_OUTPUTS, RUN, OWNER);
        verify(queue).finish(any(), eq(RegistrationStatus.SKIPPED));
    }

    @Test
    void announceInsideATransactionWaitsForTheCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            registry.announce(SourceType.RUN_INPUTS, RUN, OWNER);
            verify(pool, never()).execute(any());

            List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
            assertThat(registered).hasSize(1);
            registered.get(0).afterCommit();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(pool).execute(any());
    }

    @Test
    void aFullPoolLeavesTheRowForTheSweep() {
        doThrow(new RejectedExecutionException("full")).when(pool).execute(any());

        assertThatCode(() -> registry.announce(SourceType.RUN_OUTPUTS, RUN, OWNER)).doesNotThrowAnyException();

        verify(queue).upsertPending(SourceType.RUN_OUTPUTS, RUN, OWNER);
    }

    @Test
    void anUnclaimedRowIsLeftAlone() {
        when(queue.claim(SourceType.RUN_OUTPUTS, RUN)).thenReturn(Optional.empty());

        registry.claimAndProcess(SourceType.RUN_OUTPUTS, RUN);

        verify(runs, never()).find(any());
    }

    @Test
    void aFailedClaimNeverEscapes() {
        when(queue.claim(SourceType.RUN_OUTPUTS, RUN)).thenThrow(new DataAccessResourceFailureException("db"));

        assertThatCode(() -> registry.claimAndProcess(SourceType.RUN_OUTPUTS, RUN)).doesNotThrowAnyException();
    }

    private void givenRun(String status, List<RunRecordStep> steps) {
        when(runs.find(RUN)).thenReturn(Optional.of(new RunRecord(RUN, OWNER, WORKFLOW, "Promo", status,
                "Make a teaser", "clip.mp4", Instant.parse("2026-11-11T10:00:00Z"), steps)));
    }

    private static RegistrationRow row(SourceType sourceType) {
        return new RegistrationRow(UUID.randomUUID(), sourceType, RUN, OWNER, RegistrationStatus.PENDING, 1,
                UUID.randomUUID());
    }

    private static StoredMediaFile output(UUID owner, String objectKey, String mimeType) {
        return new StoredMediaFile(UUID.randomUUID(), owner, "file", BUCKET, objectKey, mimeType, 10, true);
    }

    private static BoundRunInput input(UUID owner, String kind, String objectKey) {
        return new BoundRunInput(UUID.randomUUID(), owner, "source", kind, BUCKET, objectKey, "application/octet-stream");
    }
}
