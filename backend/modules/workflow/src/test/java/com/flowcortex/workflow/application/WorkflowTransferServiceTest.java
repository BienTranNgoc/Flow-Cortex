package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.application.WorkflowTransferService.ImportResult;
import com.flowcortex.workflow.application.WorkflowTransferService.ReviewView;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowImportReview;
import com.flowcortex.workflow.domain.WorkflowStatus;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.WorkflowImportReviewRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** {@link WorkflowTransferService}. */
class WorkflowTransferServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID OTHER_ADMIN = UUID.randomUUID();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
    private final WorkflowStepRepository stepRepository = mock(WorkflowStepRepository.class);
    private final WorkflowImportReviewRepository reviewRepository = mock(WorkflowImportReviewRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final StepExecutorRegistry registry = new StepExecutorRegistry(mock(MediaJobs.class), Clock.systemUTC());
    private final WorkflowTransferService service = new WorkflowTransferService(workflowRepository, stepRepository,
            reviewRepository,
            new WorkflowWriteAccess(workflowRepository, registry,
                    Validation.buildDefaultValidatorFactory().getValidator()),
            new WorkflowReadinessGuard(), registry, jdbc, Clock.fixed(NOW, ZoneOffset.UTC),
            Map.of("echo", List.of("url")));

    {
        when(workflowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(stepRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reviewRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reviewRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static StepDefinition step(int order, String type, String config, String inputRef) {
        return new StepDefinition(order, "Step " + order, type, config, inputRef);
    }

    private static byte[] artifactBytes(String name, StepDefinition... steps) {
        return WorkflowArtifacts.write(name, List.of(steps), NOW).toString().getBytes(StandardCharsets.UTF_8);
    }

    private static WorkflowStep entity(UUID workflowId, StepDefinition step) {
        return new WorkflowStep(UUID.randomUUID(), workflowId, (short) step.stepOrder(), step.name(), step.type(),
                step.inputRef(), step.config());
    }

    private static WorkflowImportReview storedReview(UUID owner, byte[] artifact, boolean outbound, Instant expiresAt) {
        return new WorkflowImportReview(UUID.randomUUID(), owner, new String(artifact, StandardCharsets.UTF_8), "{}",
                outbound, artifact.length, NOW.minus(Duration.ofMinutes(1)), expiresAt);
    }

    private WorkflowImportReview openReview(UUID owner, byte[] artifact, boolean outbound) {
        WorkflowImportReview row = storedReview(owner, artifact, outbound, NOW.plus(Duration.ofMinutes(14)));
        when(reviewRepository.findLockedByToken(row.getToken())).thenReturn(Optional.of(row));
        return row;
    }

    private static void assertRefusedWith(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(UnprocessableEntityException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }

    // Export

    @Test
    void exportWritesTheLiveDefinition() {
        Workflow workflow = new Workflow(UUID.randomUUID(), UUID.randomUUID(), "Tóm tắt hằng ngày", true);
        StepDefinition first = step(1, "echo", null, null);
        StepDefinition second = step(2, "echo", "{\"profile\":\"a\"}", "{step:1.output}");
        when(workflowRepository.findActiveById(workflow.getId())).thenReturn(Optional.of(workflow));
        when(stepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId()))
                .thenReturn(List.of(entity(workflow.getId(), first), entity(workflow.getId(), second)));

        WorkflowTransferService.Exported exported = service.export(ADMIN, workflow.getId());

        assertThat(exported.filename()).isEqualTo("tom-tat-hang-ngay.workflow.json");
        assertThat(exported.artifact().get("workflow").get("name").asText()).isEqualTo("Tóm tắt hằng ngày");
        assertThat(exported.artifact().get("steps")).hasSize(2);
        assertThat(exported.artifact().get("exportedAt").asText()).isEqualTo(NOW.toString());
    }

    @Test
    void exportOfADeletedWorkflowIsNotFound() {
        UUID id = UUID.randomUUID();
        when(workflowRepository.findActiveById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.export(ADMIN, id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void exportOfProductVideoIs409() {
        Workflow productVideo = new Workflow(ProductVideoWorkflow.ID, Workflow.SYSTEM_OWNER_ID, "Product Video", true);
        when(workflowRepository.findActiveById(ProductVideoWorkflow.ID)).thenReturn(Optional.of(productVideo));

        assertThatThrownBy(() -> service.export(ADMIN, ProductVideoWorkflow.ID))
                .isInstanceOf(ConflictException.class);
        verify(stepRepository, never()).findByWorkflowIdOrderByStepOrderAsc(any());
    }

    @Test
    void anExportOverTheReviewCapIsRefusedWith422() {
        Workflow workflow = new Workflow(UUID.randomUUID(), UUID.randomUUID(), "Huge", true);
        String config = "{\"p\":\"" + "x".repeat(49_000) + "\"}";
        List<WorkflowStep> steps = new java.util.ArrayList<>();
        for (int order = 1; order <= 50; order++) {
            steps.add(entity(workflow.getId(), step(order, "echo", config, null)));
        }
        when(workflowRepository.findActiveById(workflow.getId())).thenReturn(Optional.of(workflow));
        when(stepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId())).thenReturn(steps);

        assertRefusedWith(() -> service.export(ADMIN, workflow.getId()), "ARTIFACT_TOO_LARGE");
    }

    // Review

    @Test
    void aReviewStoresTheParsedArtifactWithA15MinuteToken() {
        byte[] body = artifactBytes("Daily recap", step(1, "echo", null, null), step(2, "echo", null, null));
        when(reviewRepository.countByUserIdAndWorkflowIdIsNullAndExpiresAtAfter(ADMIN, NOW)).thenReturn(0L);

        ReviewView view = service.review(ADMIN, body);

        ArgumentCaptor<WorkflowImportReview> stored = ArgumentCaptor.forClass(WorkflowImportReview.class);
        verify(reviewRepository).saveAndFlush(stored.capture());
        assertThat(stored.getValue().getToken()).isEqualTo(view.token());
        assertThat(stored.getValue().getUserId()).isEqualTo(ADMIN);
        assertThat(stored.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(stored.getValue().getArtifactBytes()).isEqualTo(body.length);
        assertThat(stored.getValue().getWorkflowId()).isNull();
        assertThat(WorkflowArtifacts.read(stored.getValue().getArtifact().getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(WorkflowArtifacts.Parsed.class);
        assertThat(view.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(view.workflowName()).isEqualTo("Daily recap");
        assertThat(view.stepCount()).isEqualTo(2);
        assertThat(view.blocked()).isFalse();
        assertThat(view.issues()).isEmpty();
    }

    @Test
    void anUnknownStepTypeBlocksTheReviewOnThatStepsType() {
        byte[] body = artifactBytes("Daily recap", step(1, "echo", null, null), step(2, "video.teleport", null, null));

        ReviewView view = service.review(ADMIN, body);

        assertThat(view.blocked()).isTrue();
        assertThat(view.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("step_type_unavailable");
            assertThat(issue.severity()).isEqualTo("blocking");
            assertThat(issue.stepOrder()).isEqualTo(2);
            assertThat(issue.field()).isEqualTo("type");
        });
    }

    @Test
    void aReferenceToALaterStepIsAdvisoryAndOutboundDestinationsAreListed() {
        byte[] body = artifactBytes("Daily recap",
                step(1, "echo", "{\"url\":\"https://hooks.example.com/x\"}", "{step:2.text}"), step(2, "echo", null, null));

        ReviewView view = service.review(ADMIN, body);

        assertThat(view.blocked()).isFalse();
        assertThat(view.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("reference_not_earlier");
            assertThat(issue.severity()).isEqualTo("advisory");
            assertThat(issue.stepOrder()).isEqualTo(1);
            assertThat(issue.field()).isEqualTo("inputRef");
        });
        assertThat(view.outbound()).isTrue();
        assertThat(view.outboundTargets()).singleElement().satisfies(target -> {
            assertThat(target.stepOrder()).isEqualTo(1);
            assertThat(target.field()).isEqualTo("url");
            assertThat(target.value()).isEqualTo("https://hooks.example.com/x");
        });
    }

    @Test
    void anInvalidArtifactStoresNothing() {
        assertRefusedWith(() -> service.review(ADMIN, "{\"formatVersion\":2}".getBytes(StandardCharsets.UTF_8)),
                "ARTIFACT_INVALID");

        verifyNoInteractions(reviewRepository, jdbc);
    }

    @Test
    void theSixthOpenReviewIs429() {
        when(reviewRepository.countByUserIdAndWorkflowIdIsNullAndExpiresAtAfter(ADMIN, NOW)).thenReturn(5L);

        assertThatThrownBy(() -> service.review(ADMIN, artifactBytes("A", step(1, "echo", null, null))))
                .isInstanceOfSatisfying(LocalizedStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(reviewRepository, never()).saveAndFlush(any());
    }

    @Test
    void theReviewTakesTheUserLockBeforeCounting() {
        service.review(ADMIN, artifactBytes("A", step(1, "echo", null, null)));

        InOrder order = inOrder(jdbc, reviewRepository);
        order.verify(jdbc).query(eq("SELECT pg_advisory_xact_lock(hashtext(?))"), (RowCallbackHandler) any(),
                eq("workflow-import-review:" + ADMIN));
        order.verify(reviewRepository).countByUserIdAndWorkflowIdIsNullAndExpiresAtAfter(ADMIN, NOW);
        order.verify(reviewRepository).saveAndFlush(any());
    }

    // Import

    @Test
    void importCreatesADraftUnsharedWorkflowOfTheCallerWithForkingOff() {
        WorkflowImportReview row = openReview(ADMIN,
                artifactBytes("Daily recap", step(1, "echo", null, null), step(2, "echo", "{\"a\":1}", "{step:1.x}")),
                false);

        ImportResult result = service.importReviewed(ADMIN, row.getToken().toString(), null);

        ArgumentCaptor<Workflow> saved = ArgumentCaptor.forClass(Workflow.class);
        verify(workflowRepository).save(saved.capture());
        assertThat(saved.getValue().getOwnerId()).isEqualTo(ADMIN);
        assertThat(saved.getValue().getName()).isEqualTo("Daily recap");
        assertThat(saved.getValue().getStatus()).isEqualTo(WorkflowStatus.DRAFT);
        assertThat(saved.getValue().isShared()).isFalse();
        assertThat(saved.getValue().isAllowFork()).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkflowStep>> steps = ArgumentCaptor.forClass(List.class);
        verify(stepRepository).saveAll(steps.capture());
        assertThat(steps.getValue()).extracting(WorkflowStep::getWorkflowId)
                .containsOnly(saved.getValue().getId());
        assertThat(steps.getValue()).extracting(WorkflowStep::getStepOrder).containsExactly((short) 1, (short) 2);
        assertThat(steps.getValue().get(1).getConfig()).isEqualTo("{\"a\":1}");
        assertThat(steps.getValue().get(1).getInputRef()).isEqualTo("{step:1.x}");
        assertThat(result.created()).isTrue();
        assertThat(result.view().id()).isEqualTo(saved.getValue().getId());
        assertThat(result.view().steps()).hasSize(2);
    }

    @Test
    void importMarksTheReviewImportedAndDropsTheArtifactAndReview() {
        WorkflowImportReview row = openReview(ADMIN, artifactBytes("A", step(1, "echo", null, null)), false);

        ImportResult result = service.importReviewed(ADMIN, row.getToken().toString(), null);

        assertThat(row.getWorkflowId()).isEqualTo(result.view().id());
        assertThat(row.getImportedAt()).isEqualTo(NOW);
        assertThat(row.getArtifact()).isNull();
        assertThat(row.getReview()).isNull();
        verify(reviewRepository).save(row);
    }

    @Test
    void aRepeatedImportReturnsTheSameWorkflowWith200() {
        Workflow made = Workflow.draft(UUID.randomUUID(), ADMIN, "Daily recap", false);
        WorkflowImportReview row = storedReview(ADMIN, artifactBytes("A", step(1, "echo", null, null)), false,
                NOW.minus(Duration.ofHours(3)));
        row.markImported(made.getId(), NOW.minus(Duration.ofHours(3)));
        when(reviewRepository.findLockedByToken(row.getToken())).thenReturn(Optional.of(row));
        when(workflowRepository.findActiveById(made.getId())).thenReturn(Optional.of(made));
        when(stepRepository.findByWorkflowIdOrderByStepOrderAsc(made.getId()))
                .thenReturn(List.of(entity(made.getId(), step(1, "echo", null, null))));

        ImportResult result = service.importReviewed(ADMIN, row.getToken().toString(), null);

        assertThat(result.created()).isFalse();
        assertThat(result.view().id()).isEqualTo(made.getId());
        verify(workflowRepository, never()).save(any());
        verify(stepRepository, never()).saveAll(any());
    }

    @Test
    void anExpiredTokenIsRefused() {
        WorkflowImportReview row = storedReview(ADMIN, artifactBytes("A", step(1, "echo", null, null)), false,
                NOW.minusSeconds(1));
        when(reviewRepository.findLockedByToken(row.getToken())).thenReturn(Optional.of(row));

        assertRefusedWith(() -> service.importReviewed(ADMIN, row.getToken().toString(), null),
                "IMPORT_TOKEN_EXPIRED");
        verify(workflowRepository, never()).save(any());
    }

    @Test
    void anotherAdminsTokenIsRefusedLikeAnExpiredOne() {
        WorkflowImportReview row = openReview(OTHER_ADMIN, artifactBytes("A", step(1, "echo", null, null)), false);

        assertRefusedWith(() -> service.importReviewed(ADMIN, row.getToken().toString(), null),
                "IMPORT_TOKEN_EXPIRED");
        assertRefusedWith(() -> service.importReviewed(ADMIN, UUID.randomUUID().toString(), null),
                "IMPORT_TOKEN_EXPIRED");
        verify(workflowRepository, never()).save(any());
    }

    @Test
    void aMalformedTokenIsRefusedLikeAnExpiredOne() {
        assertRefusedWith(() -> service.importReviewed(ADMIN, "abc", null), "IMPORT_TOKEN_EXPIRED");
        assertRefusedWith(() -> service.importReviewed(ADMIN, null, null), "IMPORT_TOKEN_EXPIRED");

        verifyNoInteractions(reviewRepository);
    }

    @Test
    void aTypeSwitchedOffSinceTheReviewBlocksTheImport() {
        WorkflowImportReview row = openReview(ADMIN,
                artifactBytes("A", step(1, "echo", null, null), step(2, "video.teleport", null, null)), false);

        assertThatThrownBy(() -> service.importReviewed(ADMIN, row.getToken().toString(), null))
                .isInstanceOfSatisfying(UnprocessableEntityException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("IMPORT_BLOCKED");
                    assertThat(e.getErrors()).containsOnlyKeys("step2.type");
                });
        verify(workflowRepository, never()).save(any());
        assertThat(row.getWorkflowId()).isNull();
    }

    @Test
    void anOutboundReviewWithoutAcknowledgmentIsRefused() {
        WorkflowImportReview row = openReview(ADMIN,
                artifactBytes("A", step(1, "echo", "{\"url\":\"https://hooks.example.com/x\"}", null)), true);

        assertRefusedWith(() -> service.importReviewed(ADMIN, row.getToken().toString(), null),
                "OUTBOUND_ACK_REQUIRED");
        assertRefusedWith(() -> service.importReviewed(ADMIN, row.getToken().toString(), false),
                "OUTBOUND_ACK_REQUIRED");
        verify(workflowRepository, never()).save(any());
    }

    @Test
    void anOutboundReviewWithAcknowledgmentImports() {
        WorkflowImportReview row = openReview(ADMIN,
                artifactBytes("A", step(1, "echo", "{\"url\":\"https://hooks.example.com/x\"}", null)), true);

        ImportResult result = service.importReviewed(ADMIN, row.getToken().toString(), true);

        assertThat(result.created()).isTrue();
    }

    @Test
    void anExportedArtifactImportsToTheSameDefinition() throws Exception {
        Workflow source = new Workflow(UUID.randomUUID(), UUID.randomUUID(), "Tóm tắt", true);
        List<StepDefinition> definition = List.of(
                step(1, "echo", "{\"limit\":3,\"price\":2.50,\"nested\":{\"keep\":[1,2,{\"a\":null}]}}", null),
                step(2, "echo", null, "{step:1.script}"), step(3, "echo", "{\"profile\":\"a\"}", "{step:2.x}"));
        when(workflowRepository.findActiveById(source.getId())).thenReturn(Optional.of(source));
        when(stepRepository.findByWorkflowIdOrderByStepOrderAsc(source.getId()))
                .thenReturn(definition.stream().map(step -> entity(source.getId(), step)).toList());
        byte[] file = service.export(ADMIN, source.getId()).artifact().toString().getBytes(StandardCharsets.UTF_8);

        ReviewView review = service.review(ADMIN, file);
        ArgumentCaptor<WorkflowImportReview> stored = ArgumentCaptor.forClass(WorkflowImportReview.class);
        verify(reviewRepository).saveAndFlush(stored.capture());
        when(reviewRepository.findLockedByToken(review.token())).thenReturn(Optional.of(stored.getValue()));
        service.importReviewed(ADMIN, review.token().toString(), null);

        ArgumentCaptor<Workflow> workflow = ArgumentCaptor.forClass(Workflow.class);
        verify(workflowRepository).save(workflow.capture());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkflowStep>> steps = ArgumentCaptor.forClass(List.class);
        verify(stepRepository).saveAll(steps.capture());
        assertThat(workflow.getValue().getName()).isEqualTo(source.getName());
        assertThat(steps.getValue()).hasSize(3);
        for (int i = 0; i < definition.size(); i++) {
            StepDefinition expected = definition.get(i);
            WorkflowStep actual = steps.getValue().get(i);
            assertThat(actual.getStepOrder()).isEqualTo((short) expected.stepOrder());
            assertThat(actual.getName()).isEqualTo(expected.name());
            assertThat(actual.getType()).isEqualTo(expected.type());
            assertThat(actual.getInputRef()).isEqualTo(expected.inputRef());
            assertThat(configTree(actual.getConfig())).isEqualTo(configTree(expected.config()));
        }
    }

    private static JsonNode configTree(String config) throws Exception {
        return config == null ? null : JSON.readTree(config);
    }

    @Test
    void aDeletedWorkflowBehindAReplayAnswersLikeAnExpiredToken() {
        UUID gone = UUID.randomUUID();
        WorkflowImportReview row = storedReview(ADMIN, artifactBytes("A", step(1, "echo", null, null)), false,
                NOW.minus(Duration.ofHours(3)));
        row.markImported(gone, NOW.minus(Duration.ofHours(3)));
        when(reviewRepository.findLockedByToken(row.getToken())).thenReturn(Optional.of(row));
        when(workflowRepository.findActiveById(gone)).thenReturn(Optional.empty());

        assertRefusedWith(() -> service.importReviewed(ADMIN, row.getToken().toString(), null),
                "IMPORT_TOKEN_EXPIRED");
        verify(workflowRepository, never()).save(any());
    }
}
