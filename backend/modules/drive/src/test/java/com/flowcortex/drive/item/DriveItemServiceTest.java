package com.flowcortex.drive.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.drive.item.DriveItemRepository.UploadUsage;
import com.flowcortex.drive.item.DriveItemService.NewItem;
import com.flowcortex.drive.web.DriveCaller.Caller;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.StoredDriveFile;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.RunResultView;
import com.flowcortex.workflow.api.RunResultView.Output;
import com.flowcortex.workflow.api.RunResultView.Step;
import com.flowcortex.workflow.api.RunResults;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

/** Ordering of keep, insert and confirm around an uploaded file, and what a run output must be. */
class DriveItemServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");

    private final DriveItemRepository repository = mock(DriveItemRepository.class);
    private final MediaJobs mediaJobs = mock(MediaJobs.class);
    private final RunResults runResults = mock(RunResults.class);
    private final DriveItemService service = new DriveItemService(repository, mediaJobs, runResults,
            Clock.fixed(NOW, ZoneOffset.UTC), new DriveProperties(10_000L, 3L));

    private final Caller caller = new Caller(UUID.randomUUID(), UUID.randomUUID());

    @BeforeEach
    void mediaIsOn() {
        when(mediaJobs.isEnabled()).thenReturn(true);
        when(mediaJobs.isDownloadable("flowcortex-media")).thenReturn(true);
        when(repository.uploadUsage(any())).thenReturn(new UploadUsage(0, 0));
        when(repository.insertUploadWithinQuota(any(), anyLong(), anyLong())).thenReturn(true);
    }

    private StoredDriveFile stored(UUID itemId) {
        return new StoredDriveFile("flowcortex-media", "drive/" + caller.spaceId() + "/" + itemId,
                itemId + ".png", "image/png", 1234, "image");
    }

    private NewItem upload(UUID uploadId) {
        return new NewItem("Logo", "image", "logo", null, uploadId, null);
    }

    private static DriveItemRow uploadRow(UUID spaceId, UUID itemId) {
        return new DriveItemRow(itemId, spaceId, UUID.randomUUID(), "Logo", "image", "logo", null, "upload",
                "flowcortex-media", "drive/" + spaceId + "/" + itemId, itemId + ".png", "image/png", 1234L, null,
                null, NOW, NOW);
    }

    @Test
    void anUploadItemIsKeptBeforeTheInsertAndConfirmedAfterIt() {
        UUID uploadId = UUID.randomUUID();
        when(mediaJobs.keepDriveFile(any(), any(), any(), any(), any())).thenAnswer(call -> stored(call.getArgument(4)));

        DriveItemRow created = service.create(caller, upload(uploadId));

        InOrder order = inOrder(mediaJobs, repository);
        order.verify(mediaJobs).keepDriveFile(caller.userId(), uploadId, "image", caller.spaceId(), created.id());
        order.verify(repository).insertUploadWithinQuota(created, 3L, 10_000L);
        order.verify(mediaJobs).confirmDriveFile(stored(created.id()));
        assertThat(created.source()).isEqualTo("upload");
        assertThat(created.objectKey()).isEqualTo("drive/" + caller.spaceId() + "/" + created.id());
        assertThat(created.sizeBytes()).isEqualTo(1234L);
        verify(mediaJobs, never()).releaseDriveFile(any());
    }

    @Test
    void aFailedInsertReleasesTheCopy() {
        when(mediaJobs.keepDriveFile(any(), any(), any(), any(), any())).thenAnswer(call -> stored(call.getArgument(4)));
        DataIntegrityViolationException failure = new DataIntegrityViolationException("boom");
        doThrow(failure).when(repository).insertUploadWithinQuota(any(), anyLong(), anyLong());

        assertThatThrownBy(() -> service.create(caller, upload(UUID.randomUUID()))).isSameAs(failure);

        verify(mediaJobs).releaseDriveFile(any(StoredDriveFile.class));
        verify(mediaJobs, never()).confirmDriveFile(any());
    }

    @Test
    void aConfirmFailingTwiceRemovesTheRowAndAnswers503() {
        when(mediaJobs.keepDriveFile(any(), any(), any(), any(), any())).thenAnswer(call -> stored(call.getArgument(4)));
        doThrow(new IllegalStateException("store down")).when(mediaJobs).confirmDriveFile(any());

        assertThatThrownBy(() -> service.create(caller, upload(UUID.randomUUID())))
                .isInstanceOfSatisfying(LocalizedStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getDetailMessageCode()).isEqualTo("drive.storeUnavailable");
                });

        verify(mediaJobs, times(2)).confirmDriveFile(any());
        verify(repository).delete(eq(caller.spaceId()), any(UUID.class));
        verify(mediaJobs, never()).releaseDriveFile(any());
    }

    @Test
    void aConfirmThatSucceedsOnTheRetryKeepsTheItem() {
        when(mediaJobs.keepDriveFile(any(), any(), any(), any(), any())).thenAnswer(call -> stored(call.getArgument(4)));
        doThrow(new IllegalStateException("blip")).doNothing().when(mediaJobs).confirmDriveFile(any());

        DriveItemRow created = service.create(caller, upload(UUID.randomUUID()));

        assertThat(created).isNotNull();
        verify(repository, never()).delete(any(), any());
    }

    @Test
    void aRefusedUploadWritesNoRow() {
        UploadRejectedException refusal = new UploadRejectedException("contentType",
                new LocalizedMessage("drive.contentType.mismatch"));
        when(mediaJobs.keepDriveFile(any(), any(), any(), any(), any())).thenThrow(refusal);

        assertThatThrownBy(() -> service.create(caller, upload(UUID.randomUUID()))).isSameAs(refusal);

        verify(repository, never()).insertUploadWithinQuota(any(), anyLong(), anyLong());
    }

    @Test
    void anUploadIntoAFullSpaceIsRefusedBeforeAnyCopy() {
        for (UploadUsage full : List.of(new UploadUsage(3, 10), new UploadUsage(1, 10_000))) {
            when(repository.uploadUsage(caller.spaceId())).thenReturn(full);

            assertQuotaRefusal();
        }

        verify(mediaJobs, never()).keepDriveFile(any(), any(), any(), any(), any());
    }

    @Test
    void anUploadThatWouldPassTheCapAfterTheCopyIsRefusedAndTheCopyReleased() {
        when(mediaJobs.keepDriveFile(any(), any(), any(), any(), any())).thenAnswer(call -> stored(call.getArgument(4)));
        when(repository.insertUploadWithinQuota(any(), anyLong(), anyLong())).thenReturn(false);

        assertQuotaRefusal();

        verify(mediaJobs).releaseDriveFile(any(StoredDriveFile.class));
        verify(mediaJobs, never()).confirmDriveFile(any());
    }

    private void assertQuotaRefusal() {
        assertThatThrownBy(() -> service.create(caller, upload(UUID.randomUUID())))
                .isInstanceOfSatisfying(DriveValidationException.class,
                        e -> assertThat(e.errors()).containsEntry("uploadId", "drive.quotaExceeded"));
    }

    @Test
    void anUploadWithMediaOffIsRefusedWith503BeforeAnyStoreCall() {
        when(mediaJobs.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.create(caller, upload(UUID.randomUUID())))
                .isInstanceOfSatisfying(LocalizedStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getDetailMessageCode()).isEqualTo("media.disabled");
                });

        verify(mediaJobs, never()).keepDriveFile(any(), any(), any(), any(), any());
    }

    @Test
    void deletingAnUploadItemReleasesItsCopyAndARunOutputItemReleasesNothing() {
        UUID uploadItem = UUID.randomUUID();
        when(repository.find(caller.spaceId(), uploadItem)).thenReturn(Optional.of(uploadRow(caller.spaceId(), uploadItem)));
        when(repository.delete(caller.spaceId(), uploadItem)).thenReturn(1);
        UUID outputItem = UUID.randomUUID();
        DriveItemRow pointer = new DriveItemRow(outputItem, caller.spaceId(), caller.userId(), "Clip", "video",
                "driver_video", null, "run_output", "flowcortex-media", "runs/r/steps/2/final.mp4", "final.mp4",
                "video/mp4", 99L, UUID.randomUUID(), 2, NOW, NOW);
        when(repository.find(caller.spaceId(), outputItem)).thenReturn(Optional.of(pointer));
        when(repository.delete(caller.spaceId(), outputItem)).thenReturn(1);

        service.delete(caller, uploadItem);
        verify(mediaJobs).releaseDriveFile(new StoredDriveFile("flowcortex-media",
                "drive/" + caller.spaceId() + "/" + uploadItem, uploadItem + ".png", "image/png", 1234, "image"));

        org.mockito.Mockito.clearInvocations(mediaJobs);
        service.delete(caller, outputItem);
        verify(mediaJobs, never()).releaseDriveFile(any());
    }

    @Test
    void deletingAnItemOfAnotherSpaceOrAnUnknownIdIs404AndReleasesNothing() {
        UUID id = UUID.randomUUID();
        when(repository.find(caller.spaceId(), id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(caller, id)).isInstanceOf(NotFoundException.class);

        verify(mediaJobs, never()).releaseDriveFile(any());
    }

    private RunResultView completedRun(UUID ownerId, Step... steps) {
        return new RunResultView(UUID.randomUUID(), ownerId, "Dub", "text", NOW, List.of(steps));
    }

    private static Step videoStep(String status) {
        return new Step(UUID.randomUUID(), 2, "render_video", "remote", status, null, NOW,
                List.of(new Output("final.mp4", "flowcortex-media", "runs/r/steps/2/final.mp4", "video/mp4", 99L)));
    }

    private NewItem runOutput(UUID runId, int stepOrder, String name, String contentType) {
        return new NewItem("Clip", contentType, "video".equals(contentType) ? "driver_video" : "logo", null, null,
                new DriveItemService.RunOutput(runId, stepOrder, name));
    }

    @Test
    void aRunOutputMustBeADownloadableOutputOfTheCallersCompletedRunOfTheSameKind() {
        RunResultView run = completedRun(caller.userId(), videoStep("completed"));
        when(runResults.completedRun(run.runId())).thenReturn(Optional.of(run));

        DriveItemRow created = service.create(caller, runOutput(run.runId(), 2, "final.mp4", "video"));

        assertThat(created.source()).isEqualTo("run_output");
        assertThat(created.objectKey()).isEqualTo("runs/r/steps/2/final.mp4");
        assertThat(created.runId()).isEqualTo(run.runId());
        assertThat(created.stepOrder()).isEqualTo(2);
        verify(mediaJobs, never()).keepDriveFile(any(), any(), any(), any(), any());

        assertRefusedOnRunOutput(runOutput(run.runId(), 2, "final.mp4", "image"));
        assertRefusedOnRunOutput(runOutput(run.runId(), 2, "other.mp4", "video"));
        assertRefusedOnRunOutput(runOutput(run.runId(), 9, "final.mp4", "video"));

        RunResultView another = completedRun(UUID.randomUUID(), videoStep("completed"));
        when(runResults.completedRun(another.runId())).thenReturn(Optional.of(another));
        assertThatThrownBy(() -> service.create(caller, runOutput(another.runId(), 2, "final.mp4", "video")))
                .isInstanceOf(NotFoundException.class);

        UUID unknown = UUID.randomUUID();
        when(runResults.completedRun(unknown)).thenReturn(Optional.empty());
        assertRefusedOnRunOutput(runOutput(unknown, 2, "final.mp4", "video"));

        RunResultView stepNotDone = completedRun(caller.userId(), videoStep("running"));
        when(runResults.completedRun(stepNotDone.runId())).thenReturn(Optional.of(stepNotDone));
        assertRefusedOnRunOutput(runOutput(stepNotDone.runId(), 2, "final.mp4", "video"));
    }

    private void assertRefusedOnRunOutput(NewItem item) {
        assertThatThrownBy(() -> service.create(caller, item))
                .isInstanceOfSatisfying(DriveValidationException.class,
                        e -> assertThat(e.errors()).containsEntry("runOutput", "drive.runOutput.invalid"));
    }

    @Test
    void aTextItemIsStoredTrimmedWithItsText() {
        DriveItemRow created = service.create(caller,
                new NewItem("  Voice  ", "text", "brand_voice_text", "Warm and direct", null, null));

        assertThat(created.name()).isEqualTo("Voice");
        assertThat(created.textValue()).isEqualTo("Warm and direct");
        assertThat(created.source()).isEqualTo("text");
        assertThat(created.objectKey()).isNull();
        verify(repository).insert(created);
        verifyNoInteractions(runResults);
    }

    @Test
    void aRuleViolationIsRefusedBeforeAnyStoreOrDatabaseCall() {
        assertThatThrownBy(() -> service.create(caller,
                new NewItem("   ", "image", "company_bio", null, UUID.randomUUID(), null)))
                .isInstanceOfSatisfying(DriveValidationException.class, e -> assertThat(e.errors())
                        .containsEntry("name", "drive.name.invalid")
                        .containsEntry("semanticRole", "drive.role.mismatch"));

        verify(repository, never()).insert(any());
        verify(mediaJobs, never()).keepDriveFile(any(), any(), any(), any(), any());
    }
}
