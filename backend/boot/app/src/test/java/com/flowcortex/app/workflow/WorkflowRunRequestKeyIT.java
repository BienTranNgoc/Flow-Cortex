package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.flowcortex.app.chat.ChatIt;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.ProductVideoDraft;
import com.flowcortex.workflow.api.ProductVideoRuns;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import jakarta.validation.ConstraintViolationException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/** Shares the chat ITs' context: credits are on there, so every member below is funded first. */
@ChatIt
class WorkflowRunRequestKeyIT extends AbstractWorkflowHttpIT {

    @Autowired
    private ProductVideoRuns productVideoRuns;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MediaJobs mediaJobs;

    @Override
    protected UserView newUser(String prefix) {
        UserView user = super.newUser(prefix);
        await().atMost(Duration.ofSeconds(8)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM credit_wallet WHERE user_id = ?", Long.class, user.id()) == 1);
        jdbc.update("UPDATE credit_wallet SET balance = 1000000 WHERE user_id = ?", user.id());
        return user;
    }

    @BeforeEach
    void enableMedia() {
        doReturn(true).when(AopTestUtils.<MediaJobs>getUltimateTargetObject(mediaJobs)).isEnabled();
    }

    @Test
    void theSameKeyOnAGenericWorkflowReturnsTheSameRunOnce() {
        UserView owner = newUser("key-generic");
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));
        String key = "card-" + UUID.randomUUID();

        RunView first = workflowRunService.startRun(owner.id(), workflow.id(), "hi", null, null, null, false, key);
        RunView second = workflowRunService.startRun(owner.id(), workflow.id(), "hi", null, null, null, false, key);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(rowsFor(owner.id(), key)).isEqualTo(1);
    }

    @Test
    void theSameKeyOnProductVideoReturnsTheFirstRunAndCopiesNothingTheSecondTime() {
        UserView owner = newUser("key-sequential");
        List<UUID> uploads = List.of(uploadImage(owner));
        String key = "card-" + UUID.randomUUID();

        RunView first = productVideoRuns.start(owner.id(), draft(), uploads, false, key);
        RunView second = productVideoRuns.start(owner.id(), draft(), uploads, false, key);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(rowsFor(owner.id(), key)).isEqualTo(1);
        verify(mediaJobs, times(1)).copyRunInputs(any(), any());
    }

    @Test
    void twoThreadsStartingWithOneKeyEndUpWithOneRun() throws Exception {
        UserView owner = newUser("key-concurrent");
        List<UUID> uploads = List.of(uploadImage(owner));
        String key = "card-" + UUID.randomUUID();
        CountDownLatch go = new CountDownLatch(1);
        Callable<RunView> start = () -> {
            go.await();
            return productVideoRuns.start(owner.id(), draft(), uploads, false, key);
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RunView> a = pool.submit(start);
            Future<RunView> b = pool.submit(start);
            go.countDown();
            assertThat(b.get(60, TimeUnit.SECONDS).id()).isEqualTo(a.get(60, TimeUnit.SECONDS).id());
        } finally {
            pool.shutdownNow();
        }

        assertThat(rowsFor(owner.id(), key)).isEqualTo(1);
    }

    @Test
    void twoThreadsStartingAGenericWorkflowWithOneKeyEndUpWithOneRun() throws Exception {
        UserView owner = newUser("key-generic-concurrent");
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));
        String key = "card-" + UUID.randomUUID();
        CountDownLatch go = new CountDownLatch(1);
        Callable<RunView> start = () -> {
            go.await();
            return workflowRunService.startRun(owner.id(), workflow.id(), "hi", null, null, null, false, key);
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RunView> a = pool.submit(start);
            Future<RunView> b = pool.submit(start);
            go.countDown();
            assertThat(b.get(60, TimeUnit.SECONDS).id()).isEqualTo(a.get(60, TimeUnit.SECONDS).id());
        } finally {
            pool.shutdownNow();
        }

        assertThat(rowsFor(owner.id(), key)).isEqualTo(1);
    }

    @Test
    void theSameKeyFromTwoOwnersStartsTwoRuns() {
        UserView first = newUser("key-owner-a");
        UserView second = newUser("key-owner-b");
        String key = "card-" + UUID.randomUUID();

        RunView firstRun = productVideoRuns.start(first.id(), draft(), List.of(uploadImage(first)), false, key);
        RunView secondRun = productVideoRuns.start(second.id(), draft(), List.of(uploadImage(second)), false, key);

        assertThat(secondRun.id()).isNotEqualTo(firstRun.id());
        assertThat(rowsFor(first.id(), key)).isEqualTo(1);
        assertThat(rowsFor(second.id(), key)).isEqualTo(1);
    }

    @Test
    void aStartWithoutAKeyStillStartsEveryTime() {
        UserView owner = newUser("key-none");
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));

        RunView first = workflowRunService.startRun(owner.id(), workflow.id(), "hi");
        RunView second = workflowRunService.startRun(owner.id(), workflow.id(), "hi");

        assertThat(second.id()).isNotEqualTo(first.id());
    }

    @Test
    void theDraftFactoryRefusesBlankOverLongAndNonVerticalValues() {
        assertThatThrownBy(() -> ProductVideoDraft.of(" ", "desc", null, null, null, "9:16"))
                .isInstanceOf(ConstraintViolationException.class)
                .satisfies(error -> assertThat(fieldsOf(error)).containsExactly("productName"));
        assertThatThrownBy(() -> ProductVideoDraft.of("Lamp", "d".repeat(4001), null, null, null, "9:16"))
                .satisfies(error -> assertThat(fieldsOf(error)).containsExactly("description"));
        assertThatThrownBy(() -> ProductVideoDraft.of("Lamp", "desc", " ", "9".repeat(201), null, "16:9"))
                .satisfies(error -> assertThat(fieldsOf(error))
                        .containsExactlyInAnyOrder("price", "aspectRatio", "noBlankOptionalClaims"));
    }

    @Test
    void productVideoRunsRefusesAnUnvalidatedDraftAndAnUploadSelectionOfTheWrongShape() {
        UserView owner = newUser("key-refuse");
        UUID upload = uploadImage(owner);
        ProductVideoDraft blank = new ProductVideoDraft("", "desc", null, null, null, "9:16");

        assertThatThrownBy(() -> productVideoRuns.start(owner.id(), blank, List.of(upload), false, "k1"))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> productVideoRuns.start(owner.id(), draft(), List.of(upload, upload), false, "k2"))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> productVideoRuns.start(owner.id(), draft(), List.of(UUID.randomUUID(),
                UUID.randomUUID()), false, "k3"))
                .isInstanceOf(ConstraintViolationException.class);
        assertThat(rowsFor(owner.id(), "k1") + rowsFor(owner.id(), "k2") + rowsFor(owner.id(), "k3")).isZero();
    }

    @Test
    void theControllerStillAnswers400ForTheSameInvalidValues() {
        UserView owner = newUser("key-http");
        String token = loginAs(owner);
        List<UUID> uploads = List.of(uploadImage(owner));

        for (Map<String, Object> invalid : List.<Map<String, Object>>of(
                body("", "desc", "9:16", uploads),
                body("Lamp", "d".repeat(4001), "9:16", uploads),
                body("Lamp", "desc", "16:9", uploads))) {
            ResponseEntity<String> response = post("/api/v1/product-videos/runs", token, invalid, String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    private static Map<String, Object> body(String name, String description, String aspectRatio, List<UUID> uploads) {
        return Map.of("productName", name, "description", description, "uploadIds", uploads,
                "video", Map.of("aspectRatio", aspectRatio));
    }

    private static List<String> fieldsOf(Throwable error) {
        List<String> fields = new ArrayList<>();
        ((ConstraintViolationException) error).getConstraintViolations()
                .forEach(violation -> fields.add(violation.getPropertyPath().toString()));
        return fields;
    }

    private int rowsFor(UUID ownerId, String key) {
        return jdbc.queryForObject("SELECT count(*) FROM workflow_run WHERE owner_id = ? AND request_key = ?",
                Integer.class, ownerId, key);
    }

    private static ProductVideoDraft draft() {
        return ProductVideoDraft.of("Bình giữ nhiệt", "Giữ đồ uống tiện lợi", "Mua ngay", null, null, "9:16");
    }

    private UUID uploadImage(UserView owner) {
        String token = loginAs(owner);
        ResponseEntity<UploadView> grant = post("/api/v1/uploads", token,
                new UploadRequestView("product.png", 16L), UploadView.class);
        assertThat(grant.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID uploadId = grant.getBody().uploadId();
        try (S3Client store = objectStore()) {
            store.putObject(request -> request.bucket(UPLOAD_BUCKET).key(owner.id() + "/" + uploadId)
                    .contentType("image/png"), RequestBody.fromBytes("sixteen bytes!!!".getBytes(StandardCharsets.UTF_8)));
        }
        return uploadId;
    }

    record UploadRequestView(String fileName, Long size) {
    }

    record UploadView(UUID uploadId) {
    }
}
