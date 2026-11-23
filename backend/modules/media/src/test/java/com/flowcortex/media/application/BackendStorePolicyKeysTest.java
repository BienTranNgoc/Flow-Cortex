package com.flowcortex.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.media.infrastructure.MediaAssetBindingRepository;
import com.flowcortex.media.infrastructure.MediaAssetRepository;
import com.flowcortex.media.infrastructure.S3MediaStore;
import com.flowcortex.space.api.PersonalSpaces;
import jakarta.validation.Validator;
import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The key {@code copyRunObject} writes, held against {@code infra/minio/backend-policy.json} without a
 * store: {@link BackendStorePolicyIT} proves the same against a real one, where Docker is available.
 */
class BackendStorePolicyKeysTest {

    private static final String MEDIA = "flowcortex-media";
    private static final String PUT_OBJECT = "s3:PutObject";
    private static final String PUT_TAGGING = "s3:PutObjectTagging";

    private static JsonNode statements;

    @BeforeAll
    static void readThePolicy() {
        statements = JsonMapper.builder().build().readTree(new File(System.getProperty("flowcortex.backend-policy")))
                .get("Statement");
    }

    /** IAM matching: {@code *} spans any characters, slashes included; {@code ?} is one character. */
    private static boolean allows(String action, String bucket, String key) {
        String resource = bucket + "/" + key;
        for (JsonNode statement : statements) {
            if (!"Allow".equals(statement.get("Effect").asString()) || !contains(statement.get("Action"), action)) {
                continue;
            }
            for (JsonNode pattern : statement.get("Resource")) {
                String glob = pattern.asString().replace("arn:aws:s3:::", "");
                String regex = Pattern.quote(glob).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q");
                if (resource.matches(regex)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean contains(JsonNode actions, String action) {
        for (JsonNode candidate : actions) {
            if (candidate.asString().equals(action) || candidate.asString().equals("s3:*")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theEvaluatorGrantsAFirstAttemptOutputAndRefusesAStepKeyWithoutAnAttemptDirectory() {
        String run = UUID.randomUUID().toString();

        assertThat(allows(PUT_OBJECT, MEDIA, "runs/" + run + "/steps/1/attempts/1/video")).isTrue();
        assertThat(allows(PUT_OBJECT, MEDIA, "runs/" + run + "/steps/1/attempts/2/video")).isFalse();
        assertThat(allows(PUT_OBJECT, MEDIA, "runs/" + run + "/steps/1/video")).isFalse();
        assertThat(allows(PUT_TAGGING, MEDIA, "runs/" + run + "/steps/1/attempts/1/video")).isFalse();
    }

    @Test
    void theKeyAnIngestCopyIsWrittenToIsOneTheBackendMayWriteWithoutTagging() {
        S3MediaStore store = mock(S3MediaStore.class);
        when(store.head(anyString(), anyString())).thenReturn(Optional.of(new S3MediaStore.StoredObject(3L, "video/mp4")));
        MediaJobsImpl jobs = new MediaJobsImpl(mock(ApplicationEventPublisher.class), mock(Validator.class),
                properties(), store, mock(MediaAssetRepository.class), mock(MediaAssetBindingRepository.class),
                mock(PersonalSpaces.class));
        UUID run = UUID.randomUUID();

        MediaOutput copy = jobs.copyRunObject(run, 1,
                new MediaJobInput("media", MEDIA, "runs/" + run + "/inputs/media"), "video");

        assertThat(allows(PUT_OBJECT, copy.bucket(), copy.objectKey())).isTrue();
        verify(store).copyWithoutTags(MEDIA, "runs/" + run + "/inputs/media", MEDIA, copy.objectKey());
        verify(store, never()).copy(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(store, never()).copy(anyString(), anyString(), anyString(), anyString());
    }

    private static MediaProperties properties() {
        return new MediaProperties(true, Duration.ofMinutes(30),
                Map.of(
                        "probe", Duration.ofMinutes(5),
                        "extract_audio", Duration.ofMinutes(10),
                        "align_audio", Duration.ofMinutes(10),
                        "render_video", Duration.ofMinutes(10),
                        "separate_audio", Duration.ofMinutes(15),
                        "fetch_video", Duration.ofMinutes(10)),
                Duration.ofMinutes(20),
                Duration.ofMinutes(2), Duration.ofMinutes(5),
                Duration.ofMinutes(10), Duration.ofMinutes(15),
                Duration.ofMinutes(15),
                new MediaProperties.Upload(524_288_000L, 104_857_600L, 10_485_760L, List.of("mp4"), List.of("jpg"),
                        Duration.ofMinutes(15), new MediaProperties.Upload.ContentCheck(true)),
                new MediaProperties.S3("http://localhost:9000", "http://localhost:9000", "us-east-1", true,
                        "flowcortex-uploads", MEDIA, "key", "secret",
                        Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofMinutes(2)));
    }
}
