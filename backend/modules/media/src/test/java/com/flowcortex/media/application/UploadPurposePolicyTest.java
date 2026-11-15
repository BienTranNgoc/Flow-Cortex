package com.flowcortex.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.media.api.UploadPurpose;
import com.flowcortex.media.api.UploadRejectedException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UploadPurposePolicyTest {

    private static final long VIDEO_CAP = 524_288_000L;
    private static final long AUDIO_CAP = 104_857_600L;
    private static final long IMAGE_CAP = 10_485_760L;
    private static final long SUPPORT_CAP = 5_242_880L;

    private final UploadPurposePolicy policy = new UploadPurposePolicy(new MediaProperties.Upload(
            VIDEO_CAP, AUDIO_CAP, IMAGE_CAP,
            List.of("mp4", "mov", "webm", "mkv", "m4a", "mp3", "wav"),
            List.of("jpg", "jpeg", "png", "webp"),
            Duration.ofMinutes(15), new MediaProperties.Upload.ContentCheck(true)));

    @Test
    void runInputAcceptsEveryConfiguredVideoAudioAndImageExtensionAtItsKindCap() {
        record Row(String file, String type, String kind, long cap) {
        }

        List<Row> rows = List.of(
                new Row("a.mp4", "video/mp4", "video", VIDEO_CAP),
                new Row("a.MOV", "video/quicktime", "video", VIDEO_CAP),
                new Row("a.webm", "video/webm", "video", VIDEO_CAP),
                new Row("a.mkv", "video/x-matroska", "video", VIDEO_CAP),
                new Row("a.m4a", "audio/mp4", "audio", AUDIO_CAP),
                new Row("a.mp3", "audio/mpeg", "audio", AUDIO_CAP),
                new Row("a.wav", "audio/wav", "audio", AUDIO_CAP),
                new Row("a.jpg", "image/jpeg", "image", IMAGE_CAP),
                new Row("a.jpeg", "image/jpeg", "image", IMAGE_CAP),
                new Row("a.png", "image/png", "image", IMAGE_CAP),
                new Row("a.webp", "image/webp", "image", IMAGE_CAP));

        for (Row row : rows) {
            UploadPurposePolicy.Allowed allowed = policy.grant(UploadPurpose.RUN_INPUT, row.file(), row.cap());
            assertThat(allowed.contentType()).as(row.file()).isEqualTo(row.type());
            assertThat(allowed.kind()).as(row.file()).isEqualTo(row.kind());
            assertThat(allowed.maxBytes()).as(row.file()).isEqualTo(row.cap());
            assertThat(policy.maxBytes(UploadPurpose.RUN_INPUT, row.kind())).isEqualTo(row.cap());
        }
    }

    @Test
    void runInputRefusesOneByteOverTheKindCapOnSize() {
        assertThatThrownBy(() -> policy.grant(UploadPurpose.RUN_INPUT, "clip.mp4", VIDEO_CAP + 1))
                .isInstanceOfSatisfying(UploadRejectedException.class, e -> {
                    assertThat(e.field()).isEqualTo("size");
                    assertThat(e.detail().code()).isEqualTo("upload.sizeMegabytes");
                    assertThat(e.detail().args()).containsExactly(500L, ".mp4, .mov, .webm, .mkv");
                });
        assertThatThrownBy(() -> policy.grant(UploadPurpose.RUN_INPUT, "photo.png", IMAGE_CAP + 1))
                .isInstanceOfSatisfying(UploadRejectedException.class, e -> assertThat(e.field()).isEqualTo("size"));
    }

    @Test
    void runInputRefusesAnExtensionOutsideTheTableOnFileName() {
        for (String fileName : List.of("notes.pdf", "noextension", "trailing.")) {
            assertThatThrownBy(() -> policy.grant(UploadPurpose.RUN_INPUT, fileName, 16L))
                    .isInstanceOfSatisfying(UploadRejectedException.class, e -> {
                        assertThat(e.field()).as(fileName).isEqualTo("fileName");
                        assertThat(e.detail().code()).isEqualTo("upload.typeMany");
                        assertThat(e.detail().args()[1]).isEqualTo(".webp");
                    });
        }
    }

    @Test
    void supportAttachmentAcceptsPngJpgJpegWebpExactlyAtFiveMegabytes() {
        for (String fileName : List.of("a.png", "a.JPG", "a.jpeg", "a.webp")) {
            UploadPurposePolicy.Allowed allowed = policy.grant(UploadPurpose.SUPPORT_ATTACHMENT, fileName, SUPPORT_CAP);
            assertThat(allowed.kind()).as(fileName).isEqualTo("image");
            assertThat(allowed.maxBytes()).isEqualTo(SUPPORT_CAP);
        }
        assertThat(policy.grant(UploadPurpose.SUPPORT_ATTACHMENT, "a.jpg", 1L).contentType()).isEqualTo("image/jpeg");
        assertThat(policy.maxBytes(UploadPurpose.SUPPORT_ATTACHMENT, "image")).isEqualTo(SUPPORT_CAP);
        assertThat(policy.maxBytes(UploadPurpose.SUPPORT_ATTACHMENT, "video")).isZero();
    }

    @Test
    void supportAttachmentRefusesAVideoOnFileNameAndFiveMegabytesPlusOneOnSize() {
        assertThatThrownBy(() -> policy.grant(UploadPurpose.SUPPORT_ATTACHMENT, "bao-cao.mp4", 2_097_152L))
                .isInstanceOfSatisfying(UploadRejectedException.class, e -> {
                    assertThat(e.field()).isEqualTo("fileName");
                    assertThat(e.detail().code()).isEqualTo("upload.typeMany");
                });
        assertThatThrownBy(() -> policy.grant(UploadPurpose.SUPPORT_ATTACHMENT, "anh.jpg", SUPPORT_CAP + 1))
                .isInstanceOfSatisfying(UploadRejectedException.class, e -> {
                    assertThat(e.field()).isEqualTo("size");
                    assertThat(e.detail().code()).isEqualTo("upload.sizeMegabytes");
                    assertThat(e.detail().args()[0]).isEqualTo(5L);
                });
    }

    @Test
    void runInputKeyIsOwnerSlashUploadAndSupportKeyCarriesItsPrefix() {
        UUID owner = UUID.randomUUID();
        UUID upload = UUID.randomUUID();

        assertThat(policy.objectKey(UploadPurpose.RUN_INPUT, owner, upload)).isEqualTo(owner + "/" + upload);
        assertThat(policy.objectKey(UploadPurpose.SUPPORT_ATTACHMENT, owner, upload))
                .isEqualTo("support-attachment/" + owner + "/" + upload);
    }

    @Test
    void noPurposeAcceptsASizeBelowOneByte() {
        for (UploadPurpose purpose : UploadPurpose.values()) {
            for (long size : new long[] {0L, -1L}) {
                assertThatThrownBy(() -> policy.grant(purpose, "a.png", size))
                        .isInstanceOfSatisfying(UploadRejectedException.class,
                                e -> assertThat(e.field()).isEqualTo("size"));
            }
        }
    }
}
