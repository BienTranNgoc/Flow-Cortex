package com.flowcortex.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.AssetFileLink;
import com.flowcortex.media.api.AssetFiles;
import com.flowcortex.media.api.MediaStoreUnavailableException;
import com.flowcortex.media.infrastructure.S3MediaStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;

class AssetFilesImplTest {

    private static final String BUCKET = "flowcortex-media";
    private static final String KEY = "runs/4f1c/steps/1/out.mp4";
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final Instant EXPIRES = Instant.parse("2026-11-15T10:15:00Z");

    private final S3MediaStore store = mock(S3MediaStore.class);
    private final MediaProperties properties = mock(MediaProperties.class, RETURNS_DEEP_STUBS);
    private final AssetFiles files;

    AssetFilesImplTest() {
        when(properties.s3().bucket()).thenReturn(BUCKET);
        when(properties.presignTtl()).thenReturn(TTL);
        files = new AssetFilesImpl(store, properties);
    }

    private String signedDisposition(AssetFiles.Disposition disposition, String fileName) {
        when(store.presignGet(anyString(), anyString(), anyString(), any()))
                .thenReturn(new S3MediaStore.PresignedGet("http://localhost:9000/x?X-Amz-Signature=abc", EXPIRES));
        files.sign(BUCKET, KEY, disposition, fileName, "video/mp4");
        ArgumentCaptor<String> header = ArgumentCaptor.forClass(String.class);
        verify(store).presignGet(eq(BUCKET), eq(KEY), header.capture(), eq(TTL));
        return header.getValue();
    }

    @Test
    void onlyTheMediaBucketIsServed() {
        assertThat(files.serves(BUCKET)).isTrue();
        assertThat(files.serves("flowcortex-uploads")).isFalse();
        assertThat(files.serves(null)).isFalse();
    }

    @Test
    void probeOfAnotherBucketIsRefused() {
        assertThatThrownBy(() -> files.probe("flowcortex-uploads", KEY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> files.sign("flowcortex-uploads", KEY, AssetFiles.Disposition.INLINE, "a.mp4",
                "video/mp4")).isInstanceOf(IllegalArgumentException.class);

        verify(store, never()).head(anyString(), anyString());
        verify(store, never()).presignGet(anyString(), anyString(), anyString(), any());
    }

    @Test
    void probeOfAPresentObjectIsItsSize() {
        when(store.head(BUCKET, KEY)).thenReturn(Optional.of(new S3MediaStore.StoredObject(4_321L, "video/mp4")));

        assertThat(files.probe(BUCKET, KEY)).hasValue(4_321L);
    }

    @Test
    void probeOfAMissingObjectIsEmpty() {
        when(store.head(BUCKET, KEY)).thenReturn(Optional.empty());

        assertThat(files.probe(BUCKET, KEY)).isEmpty();
    }

    @Test
    void probeStoreFailureIsStoreUnavailable() {
        when(store.head(BUCKET, KEY)).thenThrow(SdkClientException.create("connect timed out"));

        assertThatThrownBy(() -> files.probe(BUCKET, KEY)).isInstanceOf(MediaStoreUnavailableException.class);
    }

    @Test
    void probeOfAForbiddenKeyIsStoreUnavailableNeverMissing() {
        when(store.head(BUCKET, KEY)).thenThrow(AwsServiceException.builder().message("403").statusCode(403).build());

        assertThatThrownBy(() -> files.probe(BUCKET, KEY)).isInstanceOf(MediaStoreUnavailableException.class);
    }

    @Test
    void signBuildsInlineDispositionWithUtf8FileNameAndAsciiFallback() {
        String header = signedDisposition(AssetFiles.Disposition.INLINE, "Áo thun.mp4");

        assertThat(header).isEqualTo("inline; filename=\"Ao thun.mp4\"; filename*=UTF-8''%C3%81o%20thun.mp4");
    }

    @Test
    void signBuildsAttachmentDisposition() {
        String header = signedDisposition(AssetFiles.Disposition.ATTACHMENT, "Đồng hồ (1).mp4");

        assertThat(header).isEqualTo(
                "attachment; filename=\"Dong ho (1).mp4\"; filename*=UTF-8''%C4%90%E1%BB%93ng%20h%E1%BB%93%20%281%29.mp4");
    }

    @Test
    void signKeepsQuotesBackslashesAndLineBreaksOutOfTheHeader() {
        String header = signedDisposition(AssetFiles.Disposition.ATTACHMENT, "a\"b\\c\r\nSet-Cookie: x.mp4");

        assertThat(header).doesNotContain("\r", "\n", "\\").doesNotContain("a\"b");
        assertThat(header).startsWith("attachment; filename=\"");
    }

    @Test
    void signReturnsTheSignedUrlAndItsExpiry() {
        when(store.presignGet(anyString(), anyString(), anyString(), any()))
                .thenReturn(new S3MediaStore.PresignedGet("http://localhost:9000/x?X-Amz-Signature=abc", EXPIRES));

        AssetFileLink link = files.sign(BUCKET, KEY, AssetFiles.Disposition.INLINE, "a.mp4", "video/mp4");

        assertThat(link.url()).isEqualTo("http://localhost:9000/x?X-Amz-Signature=abc");
        assertThat(link.expiresAt()).isEqualTo(EXPIRES);
    }

    @Test
    void signRefusesABlankFileName() {
        assertThatThrownBy(() -> files.sign(BUCKET, KEY, AssetFiles.Disposition.INLINE, " ", "video/mp4"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void linkToStringRedactsTheUrl() {
        AssetFileLink link = new AssetFileLink("http://localhost:9000/x?X-Amz-Signature=abc", EXPIRES);

        assertThat(link.toString()).doesNotContain("X-Amz-", "localhost:9000").contains(EXPIRES.toString());
    }
}
