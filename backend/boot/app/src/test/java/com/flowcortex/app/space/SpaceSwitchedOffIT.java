package com.flowcortex.app.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.space.api.SessionSpaces;
import com.flowcortex.user.api.UserService;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowRunService;
import com.flowcortex.workflow.api.WorkflowService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Default context: {@code flowcortex.spaces.enabled} is not set, and absent means off. The session
 * space is refused with 503 and writes nothing, while the activation listener still provisions.
 */
@Testcontainers
@SpringBootTest
class SpaceSwitchedOffIT extends AbstractPostgresBootTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final byte[] MP4_HEADER = {0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
        0x00, 0x00, 0x02, 0x00};

    @Autowired
    private SessionSpaces sessionSpaces;

    @Autowired
    private UserService userService;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkflowRunService workflowRunService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anAbsentPropertyMeansTheSessionSpaceAnswers503AndNothingIsWritten() {
        UUID account = UUID.randomUUID();

        assertThatThrownBy(() -> sessionSpaces.sessionSpace(account))
                .isInstanceOfSatisfying(LocalizedStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getDetailMessageCode()).isEqualTo("feature.turnedOff");
                });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM space WHERE owner_id = ?", Long.class, account)).isZero();
    }

    @Test
    void withSpacesOffAnUploadStillRegistersWithTheOwnersSpace() {
        UUID owner = userService.createInitialAdmin("space-off-upload-" + UUID.randomUUID() + "@example.com",
                "password123", "Switch Off Upload").id();
        UUID workflow = workflowService.createWorkflow(owner, "space-off-" + UUID.randomUUID(), false,
                List.of(new NewWorkflowStep("Inspect", "probe", null))).id();
        UUID uploadId = UUID.randomUUID();
        try (S3Client store = objectStore()) {
            store.putObject(o -> o.bucket(UPLOAD_BUCKET).key(owner + "/" + uploadId).contentType("video/mp4"),
                    RequestBody.fromBytes(MP4_HEADER));
        }

        RunView run = workflowRunService.startRun(owner, workflow, null, uploadId, "clip.mp4");

        assertThat(run.id()).isNotNull();
        UUID personalSpace = jdbc.queryForObject("SELECT id FROM space WHERE owner_id = ? AND is_default", UUID.class,
                owner);
        assertThat(jdbc.queryForObject("SELECT space_id FROM media_asset WHERE id = ?", UUID.class, uploadId))
                .isEqualTo(personalSpace);
    }

    @Test
    void activationStillProvisionsThePersonalSpaceWithTheSwitchOff() {
        UUID owner = userService.createInitialAdmin("space-off-" + UUID.randomUUID() + "@example.com", "password123",
                "Switch Off").id();

        Instant deadline = Instant.now().plus(TIMEOUT);
        BooleanSupplier provisioned = () -> jdbc.queryForObject(
                "SELECT count(*) FROM space_member m JOIN space s ON s.id = m.space_id "
                        + "WHERE s.owner_id = ? AND s.is_default AND m.user_id = ? AND m.role = 'manager'",
                Long.class, owner, owner) == 1;
        while (!provisioned.getAsBoolean()) {
            assertThat(Instant.now()).as("personal space provisioned within %s", TIMEOUT).isBefore(deadline);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting", e);
            }
        }
    }
}
