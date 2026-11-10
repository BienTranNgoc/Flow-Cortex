package com.flowcortex.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.media.infrastructure.MediaAssetBindingRepository;
import com.flowcortex.media.infrastructure.MediaAssetRepository;
import com.flowcortex.media.infrastructure.S3MediaStore;
import jakarta.validation.Validator;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * {@code copyStepOutputs} against a real object store holding {@code infra/minio/backend-policy.json}
 * on a non-root user, the way compose runs the backend: a prefix the code writes that the policy does
 * not grant fails here, not in a member's run retry.
 */
class BackendStorePolicyIT {

    private static final String MEDIA = "flowcortex-media";
    private static final String BACKEND_KEY = "flowcortex-backend";
    private static final String BACKEND_SECRET = "backend-secret-value";
    private static final String POLICY_IN_CONTAINER = "/tmp/backend-policy.json";

    private static final MinIOContainer STORE = new MinIOContainer(DockerImageName.parse(
            "pgsty/silo:RELEASE.2026-09-16T00-00-00Z@sha256:635197cb9f36d01bee221d34d1c7d7960f6a95c48b0b6c01d99cd13bdae51a46")
            .asCompatibleSubstituteFor("minio/minio"))
            .withCopyFileToContainer(MountableFile.forHostPath(System.getProperty("flowcortex.backend-policy")),
                    POLICY_IN_CONTAINER);

    private static S3Client root;
    private static S3MediaStore backendStore;
    private static MediaJobsImpl jobs;

    @BeforeAll
    static void startStoreWithTheBackendPolicy() throws Exception {
        STORE.start();
        String rootAlias = "http://" + STORE.getUserName() + ":" + STORE.getPassword() + "@localhost:9000";
        mc(rootAlias, "mc mb --ignore-existing ROOT/flowcortex-uploads ROOT/" + MEDIA);
        mc(rootAlias, "mc admin policy create ROOT flowcortex-backend " + POLICY_IN_CONTAINER);
        mc(rootAlias, "printf '%s\\n%s\\n' " + BACKEND_KEY + " " + BACKEND_SECRET + " | mc admin user add ROOT");
        mc(rootAlias, "mc admin policy attach ROOT flowcortex-backend --user " + BACKEND_KEY);

        root = client(STORE.getUserName(), STORE.getPassword());
        MediaProperties properties = properties(STORE.getS3URL());
        backendStore = new S3MediaStore(properties);
        jobs = new MediaJobsImpl(mock(ApplicationEventPublisher.class), mock(Validator.class), properties,
                backendStore, mock(MediaAssetRepository.class), mock(MediaAssetBindingRepository.class));
    }

    @AfterAll
    static void stop() {
        if (backendStore != null) {
            backendStore.close();
        }
        if (root != null) {
            root.close();
        }
        STORE.stop();
    }

    @Test
    void copiesAWorkerOutputIntoTheNewRunsFirstAttemptWithoutTags() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        String sourceKey = "runs/" + from + "/steps/2/attempts/3/video.mp4";
        root.putObject(request -> request.bucket(MEDIA).key(sourceKey).tagging("flowcortex-retention=run-input"),
                RequestBody.fromBytes(new byte[] {1, 2, 3}));
        MediaOutput output = new MediaOutput("video", MEDIA, sourceKey, "video/mp4", 3L, null);

        List<MediaOutput> copies = jobs.copyStepOutputs(from, to, 2, List.of(output));

        String targetKey = "runs/" + to + "/steps/2/attempts/1/video.mp4";
        assertThat(copies).extracting(MediaOutput::objectKey).containsExactly(targetKey);
        assertThat(root.getObjectAsBytes(request -> request.bucket(MEDIA).key(targetKey)).asByteArray())
                .containsExactly(1, 2, 3);
        assertThat(root.getObjectTagging(request -> request.bucket(MEDIA).key(targetKey)).tagSet()).isEmpty();
    }

    @Test
    void theBackendCannotWriteALaterAttemptOfAStep() {
        UUID from = UUID.randomUUID();
        String sourceKey = "runs/" + from + "/steps/2/attempts/1/video.mp4";
        root.putObject(request -> request.bucket(MEDIA).key(sourceKey), RequestBody.fromBytes(new byte[] {1}));

        assertThatThrownBy(() -> backendStore.copyWithoutTags(MEDIA, sourceKey, MEDIA,
                "runs/" + UUID.randomUUID() + "/steps/2/attempts/2/video.mp4"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backend store policy");
    }

    private static void mc(String rootAlias, String command) throws Exception {
        ExecResult result = STORE.execInContainer("sh", "-c", "export MC_HOST_ROOT='" + rootAlias + "'; " + command);
        assertThat(result.getExitCode()).as(command + ": " + result.getStderr()).isZero();
    }

    private static S3Client client(String accessKey, String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(STORE.getS3URL()))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().build())
                .build();
    }

    private static MediaProperties properties(String endpoint) {
        return new MediaProperties(true, Duration.ofMinutes(30),
                Map.of(
                        "probe", Duration.ofMinutes(5),
                        "extract_audio", Duration.ofMinutes(10),
                        "align_audio", Duration.ofMinutes(10),
                        "render_video", Duration.ofMinutes(10)),
                Duration.ofMinutes(20),
                Duration.ofMinutes(2), Duration.ofMinutes(5),
                Duration.ofMinutes(10), Duration.ofMinutes(15),
                Duration.ofMinutes(15),
                new MediaProperties.Upload(524_288_000L, 104_857_600L, 10_485_760L, List.of("mp4"), List.of("jpg"),
                        Duration.ofMinutes(15), new MediaProperties.Upload.ContentCheck(true)),
                new MediaProperties.S3(endpoint, endpoint, "us-east-1", true,
                        "flowcortex-uploads", MEDIA, BACKEND_KEY, BACKEND_SECRET,
                        Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofMinutes(2)));
    }
}
