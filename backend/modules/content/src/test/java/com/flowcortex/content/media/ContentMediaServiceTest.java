package com.flowcortex.content.media;

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

import com.flowcortex.content.web.ContentProblemException;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.StoredContentMedia;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.user.api.UserDirectory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

class ContentMediaServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T08:00:00Z");

    private final MediaJobs mediaJobs = mock(MediaJobs.class);
    private final ContentMediaRepository repository = mock(ContentMediaRepository.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final ContentMediaService service = new ContentMediaService(mediaJobs, repository, users,
            Clock.fixed(NOW, ZoneOffset.UTC), mock(PlatformTransactionManager.class));
    private final UUID caller = UUID.randomUUID();
    private final UUID upload = UUID.randomUUID();
    private final StoredContentMedia stored = new StoredContentMedia("flowcortex-media", "content/abc", "image/png",
            "image", 40);

    @BeforeEach
    void mediaOn() {
        when(mediaJobs.isEnabled()).thenReturn(true);
    }

    @ParameterizedTest
    @CsvSource({
            "upload.typeOne,content.media.rejected.type",
            "upload.typeMany,content.media.rejected.type",
            "upload.sizeMegabytes,content.media.rejected.size",
            "upload.sizeBytes,content.media.rejected.size",
            "upload.invalidImage,content.media.rejected.content",
            "upload.invalidVideo,content.media.rejected.content",
            "upload.imageTooLarge,content.media.rejected.pixels",
            "upload.again,content.media.rejected.uploadAgain",
            "upload.somethingNew,content.media.rejected.uploadAgain"})
    void aRefusedGrantOrRegistrationNamesTheLibraryReason(String engineCode, String expectedCode) {
        UploadRejectedException refusal = new UploadRejectedException("uploadId", new LocalizedMessage(engineCode));
        when(mediaJobs.prepareUpload(any(), any(), anyString(), anyLong())).thenThrow(refusal);
        when(mediaJobs.keepContentMedia(any(), any(), any())).thenThrow(refusal);

        for (Runnable call : List.<Runnable>of(() -> service.grant(caller, "a.png", 10),
                () -> service.register(caller, upload, "a.png"))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ContentProblemException.class, e -> {
                assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.code()).isEqualTo("content-media-rejected");
                assertThat(e.detail().code()).isEqualTo(expectedCode);
            });
        }
        verify(repository, never()).save(any());
    }

    @Test
    void aFailedInsertHandsTheKeptCopyBackAndRethrowsTheFailure() {
        when(mediaJobs.keepContentMedia(any(), any(), any())).thenReturn(stored);
        IllegalStateException failure = new IllegalStateException("insert failed");
        when(repository.save(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.register(caller, upload, "a.png")).isSameAs(failure);

        verify(mediaJobs).releaseContentMedia(stored);
    }

    @Test
    void aFailedReleaseDoesNotHideTheInsertFailure() {
        when(mediaJobs.keepContentMedia(any(), any(), any())).thenReturn(stored);
        IllegalStateException failure = new IllegalStateException("insert failed");
        when(repository.save(any())).thenThrow(failure);
        doThrow(new IllegalStateException("store down")).when(mediaJobs).releaseContentMedia(stored);

        assertThatThrownBy(() -> service.register(caller, upload, "a.png")).isSameAs(failure);
    }
}
