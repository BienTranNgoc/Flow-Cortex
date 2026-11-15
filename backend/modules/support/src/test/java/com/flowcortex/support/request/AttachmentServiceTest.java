package com.flowcortex.support.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaAssetInput;
import com.flowcortex.media.api.MediaDownload;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.StoredAttachment;
import com.flowcortex.media.api.UploadPurpose;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.support.web.SupportProblemException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(OutputCaptureExtension.class)
class AttachmentServiceTest {

    private final MediaJobs mediaJobs = mock(MediaJobs.class);
    private final AttachmentService service = new AttachmentService(mediaJobs);
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void mediaOn() {
        when(mediaJobs.isEnabled()).thenReturn(true);
    }

    @Test
    void withMediaOffAttachmentsAreUnavailableButAMessageWithoutImagesStillGoesThrough() {
        when(mediaJobs.isEnabled()).thenReturn(false);

        assertUnavailable(() -> service.grant(owner, "anh.png", 2048));
        assertUnavailable(() -> service.keep(owner, UUID.randomUUID(), UUID.randomUUID(), List.of(UUID.randomUUID())));
        assertThat(service.keep(owner, UUID.randomUUID(), UUID.randomUUID(), List.of())).isEmpty();
        verify(mediaJobs, never()).prepareUpload(any(), any(), anyString(), anyLong());
        verify(mediaJobs, never()).resolveImageUploads(any(), any(), any());
    }

    @Test
    void releasingHandsTheCopiesBackAndAStoreFailureIsLoggedNotThrown(CapturedOutput output) {
        List<StoredAttachment> kept = List.of(new StoredAttachment("flowcortex-media", "support/x/1", "image/png",
                40L));
        doThrow(new IllegalStateException("store down")).when(mediaJobs).releaseAttachments(kept);

        service.release(kept);
        service.release(List.of());

        verify(mediaJobs).releaseAttachments(kept);
        verify(mediaJobs, never()).releaseAttachments(List.of());
        assertThat(output.getOut()).contains("support.attachment outcome=release_failed count=1");
    }

    @Test
    void aGrantRefusalIsMappedByItsFieldToTheSupportSentences() {
        when(mediaJobs.prepareUpload(UploadPurpose.SUPPORT_ATTACHMENT, owner, "hoa-don.pdf", 2048))
                .thenThrow(new UploadRejectedException("fileName", new LocalizedMessage("upload.typeMany", "a", "b")));
        when(mediaJobs.prepareUpload(UploadPurpose.SUPPORT_ATTACHMENT, owner, "anh.jpg", 5L * 1024 * 1024 + 1))
                .thenThrow(new UploadRejectedException("size", new LocalizedMessage("upload.sizeMegabytes", 5, "x")));

        assertRejected(() -> service.grant(owner, "hoa-don.pdf", 2048), "support.attachment.typeNotAllowed");
        assertRejected(() -> service.grant(owner, "anh.jpg", 5L * 1024 * 1024 + 1), "support.attachment.tooLarge");
    }

    @Test
    void aFourthOrRepeatedImageIsRefused() {
        UUID id = UUID.randomUUID();

        assertRejected(() -> AttachmentService.checkCount(List.of(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID())), "support.attachment.tooMany");
        assertRejected(() -> AttachmentService.checkCount(List.of(id, id)), "support.attachment.duplicate");
        assertThat(AttachmentService.checkCount(null)).isEmpty();
    }

    @Test
    void keptImagesGoUnderTheRequestAndMessageAndARefusalReadsAsNotAnImage() {
        UUID request = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        UUID upload = UUID.randomUUID();
        List<MediaAssetInput> inputs = List.of(new MediaAssetInput(upload,
                new MediaJobInput("image_1", "flowcortex-uploads", owner + "/" + upload)));
        StoredAttachment kept = new StoredAttachment("flowcortex-media", "support/x/1", "image/png", 40L);
        when(mediaJobs.resolveImageUploads(UploadPurpose.SUPPORT_ATTACHMENT, owner, List.of(upload)))
                .thenReturn(inputs);
        when(mediaJobs.keepAttachments("support/" + request + "/" + message, inputs, AttachmentService.MAX_BYTES))
                .thenReturn(List.of(kept))
                .thenThrow(new UploadRejectedException("uploadIds", new LocalizedMessage("upload.attachmentNotImage")))
                .thenThrow(new UploadRejectedException("uploadIds", new LocalizedMessage("upload.imageTooLarge")));

        assertThat(service.keep(owner, request, message, List.of(upload))).containsExactly(kept);
        assertRejected(() -> service.keep(owner, request, message, List.of(upload)),
                "support.attachment.notImage");
        assertRejected(() -> service.keep(owner, request, message, List.of(upload)),
                "support.attachment.tooManyPixels");
        assertThat(service.keep(owner, request, message, List.of())).isEmpty();
    }

    @Test
    void anImageDownloadIsNamedAfterTheRequestAndPosition() {
        MediaDownload download = new MediaDownload("https://store/get", Instant.now());
        when(mediaJobs.presignedGet("flowcortex-media", "support/x/1", "yeu-cau-12-anh-2.jpg")).thenReturn(download);

        assertThat(service.download("flowcortex-media", "support/x/1", 12, 2, "image/jpeg")).isSameAs(download);
    }

    private static void assertUnavailable(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SupportProblemException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.code()).isEqualTo("attachments-unavailable");
        });
    }

    private static void assertRejected(Runnable call, String detailKey) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SupportProblemException.class, e -> {
            assertThat(e.code()).isEqualTo("attachment-rejected");
            assertThat(e.detail().code()).isEqualTo(detailKey);
        });
    }
}
