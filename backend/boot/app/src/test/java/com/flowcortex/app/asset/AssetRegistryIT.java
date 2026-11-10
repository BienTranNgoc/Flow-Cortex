package com.flowcortex.app.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.asset.application.AssetRegistrationPool;
import com.flowcortex.asset.application.AssetRegistry;
import com.flowcortex.asset.domain.RegistrationRow;
import com.flowcortex.asset.domain.RegistrationStatus;
import com.flowcortex.asset.domain.SourceType;
import com.flowcortex.asset.infrastructure.AssetRegistrationStore;
import com.flowcortex.media.api.MediaStoreUnavailableException;
import com.flowcortex.media.api.RegistrableMedia;
import com.flowcortex.media.api.RunInputsBound;
import com.flowcortex.space.api.PersonalSpaces;
import com.flowcortex.workflow.api.WorkflowRunCompleted;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Asset registration end to end against a real Postgres: runs, steps and stored files are seeded with
 * SQL, processing is driven through the claimed queue row (or through the published events), and the
 * outcome is read from {@code space_asset} and the queue row. The object store's retag is stubbed; every
 * read is real.
 */
@Testcontainers
@SpringBootTest
class AssetRegistryIT extends AbstractPostgresBootTest {

    /** What the stubbed object store reports for a kept copy; the seeded uploads are 50 bytes. */
    private static final long COPY_BYTES = 321L;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AssetRegistry registry;

    @Autowired
    private AssetRegistrationStore queue;

    @Autowired
    private PersonalSpaces spaces;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactions;

    @MockitoSpyBean
    private RegistrableMedia media;

    @MockitoSpyBean
    private AssetRegistrationPool pool;

    private final List<UUID> owners = new ArrayList<>();

    @BeforeEach
    void keepEverythingByDefault() {
        doReturn(OptionalLong.of(COPY_BYTES)).when(media).keepRunInput(any(), any());
    }

    @AfterEach
    void clean() {
        for (UUID owner : owners) {
            jdbc.update("DELETE FROM space_asset WHERE owner_id = ?", owner);
            jdbc.update("DELETE FROM space_asset_registration WHERE owner_id = ?", owner);
            jdbc.update("DELETE FROM media_asset_binding WHERE run_id IN (SELECT id FROM workflow_run WHERE owner_id = ?)",
                    owner);
            jdbc.update("DELETE FROM media_asset WHERE owner_id = ?", owner);
            jdbc.update("DELETE FROM run_step WHERE run_id IN (SELECT id FROM workflow_run WHERE owner_id = ?)", owner);
            jdbc.update("DELETE FROM workflow_run WHERE owner_id = ?", owner);
            jdbc.update("DELETE FROM workflow WHERE owner_id = ?", owner);
        }
        owners.clear();
    }

    @Test
    void aCompletedRunRegistersItsFinalVideoInTheOwnersSpace() {
        UUID owner = newOwner();
        UUID workflow = workflow(owner, "Dub");
        UUID run = run(owner, workflow, "completed", "xin chào");
        UUID audioStep = step(run, 1, "tts");
        UUID videoStep = step(run, 2, "burn");
        output(owner, audioStep, "audio/mpeg", "runs/" + run + "/steps/1/a.mp3");
        UUID video = output(owner, videoStep, "video/mp4", "runs/" + run + "/steps/2/out.mp4");

        process(SourceType.RUN_OUTPUTS, run, owner);

        Map<String, Object> asset = jdbc.queryForMap("SELECT * FROM space_asset WHERE run_id = ?", run);
        assertThat(asset.get("space_id")).isEqualTo(spaces.personalSpaceOf(owner));
        assertThat(asset.get("owner_id")).isEqualTo(owner);
        assertThat(asset.get("media_asset_id")).isEqualTo(video);
        assertThat(asset.get("source")).isEqualTo("run");
        assertThat(asset.get("kind")).isEqualTo("video");
        assertThat(asset.get("title")).isEqualTo("xin chào · Dub");
        assertThat(asset.get("run_step_id")).isEqualTo(videoStep);
        assertThat(asset.get("workflow_id")).isEqualTo(workflow);
        assertThat(asset.get("workflow_name")).isEqualTo("Dub");
        assertThat(rowStatus(SourceType.RUN_OUTPUTS, run)).isEqualTo("done");
    }

    @Test
    void aJsonManifestBesideTheVideoIsNotAnAsset() {
        UUID owner = newOwner();
        UUID run = run(owner, workflow(owner, "Dub"), "completed", "x");
        UUID step = step(run, 1, "burn");
        UUID video = output(owner, step, "video/mp4", "runs/" + run + "/steps/1/out.mp4");
        UUID manifest = output(owner, step, "application/json", "runs/" + run + "/steps/1/manifest.json");

        process(SourceType.RUN_OUTPUTS, run, owner);

        assertThat(assetMediaIds(owner)).containsExactly(video).doesNotContain(manifest);
        assertThat(rowStatus(SourceType.RUN_OUTPUTS, run)).isEqualTo("done");
    }

    @Test
    void aSecondRunWithTheSameInputIsTitledTwo() {
        UUID owner = newOwner();
        UUID workflow = workflow(owner, "Dub");
        UUID first = completedRunWithVideo(owner, workflow, "same input");
        UUID second = completedRunWithVideo(owner, workflow, "same input");

        process(SourceType.RUN_OUTPUTS, first, owner);
        process(SourceType.RUN_OUTPUTS, second, owner);

        assertThat(titles(first)).containsExactly("same input · Dub");
        assertThat(titles(second)).containsExactly("same input · Dub (2)");
    }

    @Test
    void twoFinalOutputsAreTitledOneAndTwo() {
        UUID owner = newOwner();
        UUID run = run(owner, workflow(owner, "Dub"), "completed", "pair");
        UUID step = step(run, 1, "burn");
        output(owner, step, "video/mp4", "runs/" + run + "/steps/1/a.mp4");
        output(owner, step, "video/mp4", "runs/" + run + "/steps/1/b.mp4");

        process(SourceType.RUN_OUTPUTS, run, owner);

        assertThat(jdbc.queryForList("SELECT title FROM space_asset WHERE run_id = ? ORDER BY object_key",
                String.class, run)).containsExactly("pair · Dub · 1", "pair · Dub · 2");
    }

    @Test
    void aProductVideoRunIsTitledByItsProductNameAndItsImageByWorkflowNameAndKey() {
        UUID owner = newOwner();
        UUID workflow = workflow(owner, "Product video");
        UUID run = run(owner, workflow, "completed",
                "{\"productName\":\"Áo thun cotton trắng\",\"description\":\"d\"}");
        jdbc.update("INSERT INTO product_video_input (run_id, product_name, description, cta, aspect_ratio, "
                + "created_at) VALUES (?, 'Áo thun cotton trắng', 'd', 'c', '9:16', now())", run);
        UUID step = step(run, 1, "render");
        output(owner, step, "video/mp4", "runs/" + run + "/steps/1/out.mp4");
        bindUpload(owner, run, "image_1", "image/png", 0);

        process(SourceType.RUN_OUTPUTS, run, owner);
        process(SourceType.RUN_INPUTS, run, owner);

        assertThat(jdbc.queryForList("SELECT title FROM space_asset WHERE run_id = ? ORDER BY source", String.class,
                run)).containsExactly("Áo thun cotton trắng · Product video", "Product video · image_1");
        assertThat(jdbc.queryForList("SELECT title FROM space_asset WHERE run_id = ?", String.class, run))
                .noneMatch(title -> title.contains("{"));
    }

    @Test
    void aMediaRunsSingleUploadIsTitledByItsFileName() {
        UUID owner = newOwner();
        UUID run = run(owner, workflow(owner, "Render"), "running", "my clip.mp4");
        step(run, 1, "render_video");
        bindUpload(owner, run, "video", "video/mp4", 0);

        process(SourceType.RUN_INPUTS, run, owner);

        assertThat(titles(run)).containsExactly("my clip.mp4");
    }

    @Test
    void boundInputsBecomeUploadAssetsPointingAtTheRunCopyAndAreKept() {
        UUID owner = newOwner();
        UUID run = run(owner, workflow(owner, "Dub"), "running", "text");
        step(run, 1, "tts");
        UUID first = bindUpload(owner, run, "image_1", "image/png", 0);
        UUID second = bindUpload(owner, run, "image_2", "image/jpeg", 1);

        process(SourceType.RUN_INPUTS, run, owner);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM space_asset WHERE run_id = ? ORDER BY object_key", run);
        assertThat(rows).extracting(row -> row.get("media_asset_id")).containsExactly(first, second);
        assertThat(rows).extracting(row -> row.get("object_key"))
                .containsExactly("runs/" + run + "/inputs/image_1", "runs/" + run + "/inputs/image_2");
        assertThat(rows).extracting(row -> row.get("source")).containsOnly("upload");
        assertThat(rows).extracting(row -> row.get("bytes")).containsOnly(COPY_BYTES);
        assertThat(rows).extracting(row -> row.get("run_step_id")).containsOnlyNulls();
        assertThat(rows).extracting(row -> row.get("space_id")).containsOnly(spaces.personalSpaceOf(owner));
        verify(media).keepRunInput(MEDIA_BUCKET, "runs/" + run + "/inputs/image_1");
        verify(media).keepRunInput(MEDIA_BUCKET, "runs/" + run + "/inputs/image_2");
        assertThat(rowStatus(SourceType.RUN_INPUTS, run)).isEqualTo("done");
    }

    @Test
    void aMissingCopyIsSkippedAndWritesNoAsset() {
        UUID owner = newOwner();
        UUID run = run(owner, workflow(owner, "Dub"), "running", "text");
        step(run, 1, "tts");
        bindUpload(owner, run, "image_1", "image/png", 0);
        doReturn(OptionalLong.empty()).when(media).keepRunInput(any(), any());

        process(SourceType.RUN_INPUTS, run, owner);

        assertThat(titles(run)).isEmpty();
        assertThat(rowStatus(SourceType.RUN_INPUTS, run)).isEqualTo("done");
    }

    @Test
    void aStoreFailureLeavesTheInputsRowPendingWithStoreUnavailable() {
        UUID owner = newOwner();
        UUID run = run(owner, workflow(owner, "Dub"), "running", "text");
        step(run, 1, "tts");
        bindUpload(owner, run, "image_1", "image/png", 0);
        doThrow(new MediaStoreUnavailableException("down", new RuntimeException("boom")))
                .when(media).keepRunInput(any(), any());

        process(SourceType.RUN_INPUTS, run, owner);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, attempts, last_error_code, lease_token FROM space_asset_registration "
                        + "WHERE source_type = 'run_inputs' AND source_id = ?", run);
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("last_error_code")).isEqualTo("store_unavailable");
        assertThat(row.get("lease_token")).isNull();
        assertThat(titles(run)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_run WHERE id = ?", String.class, run))
                .isEqualTo("running");
    }

    @Test
    void theSameUploadBoundToASecondRunStaysOneAsset() {
        UUID owner = newOwner();
        UUID workflow = workflow(owner, "Dub");
        UUID first = run(owner, workflow, "running", "text");
        step(first, 1, "tts");
        UUID second = run(owner, workflow, "running", "text");
        step(second, 1, "tts");
        UUID upload = upload(owner, "image/png");
        bind(first, "image_1", upload, 0);
        bind(second, "image_1", upload, 0);

        process(SourceType.RUN_INPUTS, first, owner);
        process(SourceType.RUN_INPUTS, second, owner);

        assertThat(assetMediaIds(owner)).containsExactly(upload);
    }

    @Test
    void aReboundUploadKeepsNoSecondCopy() {
        UUID owner = newOwner();
        UUID workflow = workflow(owner, "Dub");
        UUID first = run(owner, workflow, "running", "text");
        step(first, 1, "tts");
        UUID second = run(owner, workflow, "running", "text");
        step(second, 1, "tts");
        UUID upload = upload(owner, "image/png");
        bind(first, "image_1", upload, 0);
        bind(second, "image_1", upload, 0);

        process(SourceType.RUN_INPUTS, first, owner);
        process(SourceType.RUN_INPUTS, second, owner);

        verify(media, never()).keepRunInput(MEDIA_BUCKET, "runs/" + second + "/inputs/image_1");
        assertThat(rowStatus(SourceType.RUN_INPUTS, second)).isEqualTo("done");
    }

    @Test
    void aSupportAttachmentNeverBecomesAnAsset() {
        UUID owner = newOwner();
        UUID space = spaces.personalSpaceOf(owner);
        UUID attachment = UUID.randomUUID();
        jdbc.update("INSERT INTO media_asset (id, owner_id, space_id, kind, bucket, object_key, mime_type, bytes, "
                + "status, created_at) VALUES (?, ?, ?, 'image', ?, ?, 'image/png', 10, 'ready', now())",
                attachment, owner, space, MEDIA_BUCKET, "support/" + UUID.randomUUID() + "/" + UUID.randomUUID() + "/1");
        UUID run = completedRunWithVideo(owner, workflow(owner, "Dub"), "text");

        process(SourceType.RUN_OUTPUTS, run, owner);
        process(SourceType.RUN_INPUTS, run, owner);

        assertThat(assetMediaIds(owner)).doesNotContain(attachment);
    }

    @Test
    void aForeignOwnersObjectIsSkipped() {
        UUID owner = newOwner();
        UUID stranger = newOwner();
        UUID run = run(owner, workflow(owner, "Dub"), "completed", "x");
        UUID step = step(run, 1, "burn");
        output(stranger, step, "video/mp4", "runs/" + run + "/steps/1/out.mp4");

        process(SourceType.RUN_OUTPUTS, run, owner);

        assertThat(titles(run)).isEmpty();
        assertThat(rowStatus(SourceType.RUN_OUTPUTS, run)).isEqualTo("done");
    }

    @Test
    void processingASourceTwiceCreatesNoSecondAssetAndChangesNoTitle() {
        UUID owner = newOwner();
        UUID run = completedRunWithVideo(owner, workflow(owner, "Dub"), "again");
        process(SourceType.RUN_OUTPUTS, run, owner);
        List<String> before = titles(run);

        registry.process(new RegistrationRow(UUID.randomUUID(), SourceType.RUN_OUTPUTS, run, owner,
                RegistrationStatus.PENDING, 0, UUID.randomUUID()));

        assertThat(titles(run)).isEqualTo(before).hasSize(1);
    }

    @Test
    void aRunNotCompletedIsSkipped() {
        UUID owner = newOwner();
        UUID run = run(owner, workflow(owner, "Dub"), "running", "x");
        UUID step = step(run, 1, "burn");
        output(owner, step, "video/mp4", "runs/" + run + "/steps/1/out.mp4");

        process(SourceType.RUN_OUTPUTS, run, owner);

        assertThat(titles(run)).isEmpty();
        assertThat(rowStatus(SourceType.RUN_OUTPUTS, run)).isEqualTo("skipped");
    }

    @Test
    void aRetriedRunKeepsOneVersionForAllItsOutputs() {
        UUID owner = newOwner();
        UUID space = spaces.personalSpaceOf(owner);
        UUID workflow = workflow(owner, "Dub");
        UUID retried = run(owner, workflow, "completed", "retry");
        UUID step = step(retried, 1, "burn");
        UUID firstOutput = output(owner, step, "video/mp4", "runs/" + retried + "/steps/1/a.mp4");
        output(owner, step, "video/mp4", "runs/" + retried + "/steps/1/b.mp4");
        insertAsset(owner, space, firstOutput, retried, step, "retry · Dub (2) · 1", "retry · Dub", 2,
                "runs/" + retried + "/steps/1/a.mp4");
        UUID other = completedRunWithVideo(owner, workflow, "retry");
        process(SourceType.RUN_OUTPUTS, other, owner);

        process(SourceType.RUN_OUTPUTS, retried, owner);

        assertThat(jdbc.queryForList("SELECT title FROM space_asset WHERE run_id = ? ORDER BY object_key",
                String.class, retried)).containsExactly("retry · Dub (2) · 1", "retry · Dub (2) · 2");
        assertThat(titles(other)).containsExactly("retry · Dub (3)");
    }

    @Test
    void completingARunThroughTheEngineRegistersItsOutputsAfterCommit() {
        UUID owner = newOwner();
        UUID run = completedRunWithVideo(owner, workflow(owner, "Dub"), "engine");
        List<String> threads = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            threads.add(Thread.currentThread().getName());
            return call.callRealMethod();
        }).when(media).stepOutputs(any(), anyShort());

        transactions.executeWithoutResult(status -> events.publishEvent(new WorkflowRunCompleted(run, owner)));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(titles(run)).hasSize(1));
        assertThat(threads).isNotEmpty().allMatch(name -> name.startsWith("asset-registration-"));
    }

    @Test
    void boundInputsPublishedAfterCommitAreRegistered() {
        UUID owner = newOwner();
        UUID run = run(owner, workflow(owner, "Dub"), "running", "text");
        step(run, 1, "tts");
        bindUpload(owner, run, "image_1", "image/png", 0);

        transactions.executeWithoutResult(status -> events.publishEvent(new RunInputsBound(run, owner)));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(titles(run)).hasSize(1));
    }

    @Test
    void announcingInsideATransactionHandsOffOnlyAfterTheRowIsCommitted() {
        UUID owner = newOwner();
        UUID run = completedRunWithVideo(owner, workflow(owner, "Dub"), "later");

        transactions.executeWithoutResult(status -> {
            registry.announce(SourceType.RUN_OUTPUTS, run, owner);
            verify(pool, never()).execute(any());
        });

        verify(pool).execute(any());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(titles(run)).hasSize(1));
    }

    @Test
    void anAnnouncementRolledBackHandsNothingOff() {
        UUID owner = newOwner();
        UUID run = completedRunWithVideo(owner, workflow(owner, "Dub"), "undone");

        transactions.executeWithoutResult(status -> {
            registry.announce(SourceType.RUN_OUTPUTS, run, owner);
            status.setRollbackOnly();
        });

        verify(pool, never()).execute(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM space_asset_registration WHERE source_id = ?",
                Integer.class, run)).isZero();
    }

    @Test
    void aRejectedHandOffLeavesTheRowPendingAndDue() {
        UUID owner = newOwner();
        UUID run = completedRunWithVideo(owner, workflow(owner, "Dub"), "busy");
        doThrow(new RejectedExecutionException("full")).when(pool).execute(any());

        registry.announce(SourceType.RUN_OUTPUTS, run, owner);

        Map<String, Object> row = jdbc.queryForMap("SELECT status, lease_token, next_attempt_at "
                + "FROM space_asset_registration WHERE source_type = 'run_outputs' AND source_id = ?", run);
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat(row.get("lease_token")).isNull();
        assertThat(((Timestamp) row.get("next_attempt_at")).toInstant()).isBeforeOrEqualTo(Instant.now());
        assertThat(titles(run)).isEmpty();
        verify(pool).execute(any());
        verify(media, never()).stepOutputs(any(), eq((short) 1));
    }

    private void process(SourceType type, UUID run, UUID owner) {
        queue.upsertPending(type, run, owner);
        RegistrationRow row = queue.claim(type, run).orElseThrow();
        registry.process(row);
    }

    private UUID newOwner() {
        UUID owner = UUID.randomUUID();
        owners.add(owner);
        spaces.personalSpaceOf(owner);
        return owner;
    }

    private UUID workflow(UUID owner, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at) "
                + "VALUES (?, ?, ?, false, now(), now())", id, owner, name);
        return id;
    }

    private UUID run(UUID owner, UUID workflow, String status, String input) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, finished_at) "
                + "VALUES (?, ?, ?, ?, ?, now(), ?)", id, workflow, owner, status, input,
                "completed".equals(status) ? Timestamp.from(Instant.now()) : null);
        return id;
    }

    private UUID step(UUID run, int order, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status) VALUES (?, ?, ?, ?, 'completed')",
                id, run, order, type);
        return id;
    }

    private UUID output(UUID owner, UUID step, String mime, String key) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO media_asset (id, owner_id, space_id, kind, bucket, object_key, mime_type, bytes, "
                + "status, source_run_step_id, source_attempt, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 100, 'ready', ?, 1, now())",
                id, owner, spaces.personalSpaceOf(owner), mime.startsWith("video") ? "video" : "other", MEDIA_BUCKET,
                key, mime, step);
        return id;
    }

    private UUID upload(UUID owner, String mime) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO media_asset (id, owner_id, space_id, kind, bucket, object_key, mime_type, bytes, "
                + "status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 50, 'ready', now())",
                id, owner, spaces.personalSpaceOf(owner), mime.substring(0, mime.indexOf('/')), UPLOAD_BUCKET,
                owner + "/" + id, mime);
        return id;
    }

    private void bind(UUID run, String key, UUID upload, int position) {
        jdbc.update("INSERT INTO media_asset_binding (run_id, binding_key, asset_id, position, created_at) "
                + "VALUES (?, ?, ?, ?, now())", run, key, upload, position);
    }

    private UUID bindUpload(UUID owner, UUID run, String key, String mime, int position) {
        UUID upload = upload(owner, mime);
        bind(run, key, upload, position);
        return upload;
    }

    private UUID completedRunWithVideo(UUID owner, UUID workflow, String input) {
        UUID run = run(owner, workflow, "completed", input);
        UUID step = step(run, 1, "burn");
        output(owner, step, "video/mp4", "runs/" + run + "/steps/1/out.mp4");
        return run;
    }

    private void insertAsset(UUID owner, UUID space, UUID mediaAsset, UUID run, UUID step, String title,
            String base, int version, String key) {
        jdbc.update("INSERT INTO space_asset (id, space_id, owner_id, media_asset_id, source, kind, title, "
                + "title_base, title_version, bucket, object_key, mime_type, bytes, run_id, run_step_id, created_at) "
                + "VALUES (?, ?, ?, ?, 'run', 'video', ?, ?, ?, ?, ?, 'video/mp4', 100, ?, ?, now())",
                UUID.randomUUID(), space, owner, mediaAsset, title, base, version, MEDIA_BUCKET, key, run, step);
    }

    private List<String> titles(UUID run) {
        return jdbc.queryForList("SELECT title FROM space_asset WHERE run_id = ? ORDER BY object_key", String.class,
                run);
    }

    private List<UUID> assetMediaIds(UUID owner) {
        return jdbc.queryForList("SELECT media_asset_id FROM space_asset WHERE owner_id = ?", UUID.class, owner);
    }

    private String rowStatus(SourceType type, UUID run) {
        return jdbc.queryForObject("SELECT status FROM space_asset_registration WHERE source_type = ? AND "
                + "source_id = ?", String.class, type.column(), run);
    }
}
