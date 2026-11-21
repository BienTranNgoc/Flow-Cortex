package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.workflow.api.ChatWorkflowView;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowVersionRepository;
import jakarta.validation.Validation;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link WorkflowServiceImpl#listChatCatalog}. */
class ChatCatalogTest {

    private final WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
    private final WorkflowStepRepository workflowStepRepository = mock(WorkflowStepRepository.class);
    private final CreditAccounts credits = mock(CreditAccounts.class);
    private final StepExecutorRegistry registry =
            new StepExecutorRegistry(mock(MediaJobs.class), Clock.systemUTC());
    private final WorkflowVersionRepository versionRepository = mock(WorkflowVersionRepository.class);
    private final WorkflowServiceImpl service = new WorkflowServiceImpl(workflowRepository, workflowStepRepository,
            registry,
            new WorkflowWriteAccess(workflowRepository, registry,
                    Validation.buildDefaultValidatorFactory().getValidator()),
            new VersionWriter(versionRepository, workflowRepository, workflowStepRepository),
            new ServedDefinitions(workflowStepRepository, versionRepository), credits);
    private final UUID memberId = UUID.randomUUID();

    @Test
    void aWorkflowWhoseFirstStepIsInlineTakesText() {
        Workflow workflow = visible(UUID.randomUUID(), "Echo", "echo", "fail");

        List<ChatWorkflowView> catalog = service.listChatCatalog(memberId);

        assertThat(catalog).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo(workflow.getId());
            assertThat(entry.name()).isEqualTo("Echo");
            assertThat(entry.stepTypes()).containsExactly("echo", "fail");
            assertThat(entry.inputKind()).isEqualTo("text");
        });
    }

    @Test
    void aWorkflowWhoseFirstStepIsRemoteTakesAnImage() {
        visible(UUID.randomUUID(), "Probe", "probe", "echo");

        assertThat(service.listChatCatalog(memberId)).singleElement()
                .satisfies(entry -> assertThat(entry.inputKind()).isEqualTo("image"));
    }

    @Test
    void theProductVideoWorkflowIsItsOwnKindWhateverItsFirstStep() {
        visible(ProductVideoWorkflow.ID, "Product Video", "generate_script", "render_video");

        assertThat(service.listChatCatalog(memberId)).singleElement()
                .satisfies(entry -> assertThat(entry.inputKind()).isEqualTo("product_video"));
    }

    @Test
    void estimatedCreditsMatchTheRunFormWhileCreditsAreOn() {
        visible(UUID.randomUUID(), "Echo", "echo", "fail");
        when(credits.enabled()).thenReturn(true);
        when(credits.estimate(List.of("echo", "fail"))).thenReturn(42L);

        assertThat(service.listChatCatalog(memberId)).singleElement()
                .satisfies(entry -> assertThat(entry.estimatedCredits()).isEqualTo(42L));
    }

    @Test
    void estimatedCreditsAreNullWhileCreditsAreOff() {
        visible(UUID.randomUUID(), "Echo", "echo");
        when(credits.enabled()).thenReturn(false);

        assertThat(service.listChatCatalog(memberId)).singleElement()
                .satisfies(entry -> assertThat(entry.estimatedCredits()).isNull());
    }

    private Workflow visible(UUID id, String name, String... types) {
        Workflow workflow = new Workflow(id, UUID.randomUUID(), name, true);
        List<WorkflowStep> steps = new java.util.ArrayList<>();
        for (int i = 0; i < types.length; i++) {
            steps.add(new WorkflowStep(UUID.randomUUID(), id, (short) (i + 1), "s" + i, types[i], null));
        }
        when(workflowRepository.findAllVisibleTo(memberId)).thenReturn(List.of(workflow));
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(id)).thenReturn(steps);
        return workflow;
    }
}
