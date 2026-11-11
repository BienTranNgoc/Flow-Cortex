package com.flowcortex.workflow.application;

import static com.flowcortex.workflow.EnglishBundle.english;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.RunStartGuard.StartContext;
import com.flowcortex.workflow.api.RunStartGuard.StepDef;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

class ConnectorStepOwnerGuardTest {

    private static final UUID AUTHOR = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID WORKFLOW = UUID.randomUUID();
    private static final StartContext CONTEXT = new StartContext(false);
    private static final String CONNECTOR_CONFIG = "{\"connector\":\"notion-main\",\"url\":\"https://api.notion.com/v1/users\"}";

    private final WorkflowRepository workflows = mock(WorkflowRepository.class);
    private final ConnectorStepOwnerGuard guard = new ConnectorStepOwnerGuard(workflows);

    private static StepDef echo(int order) {
        return new StepDef(order, "Echo", "echo", null, null);
    }

    private static StepDef connector(int order) {
        return new StepDef(order, "Call", "connector.call", CONNECTOR_CONFIG, null);
    }

    private void workflowOwnedBy(UUID owner) {
        when(workflows.findById(WORKFLOW)).thenReturn(Optional.of(new Workflow(WORKFLOW, owner, "probe", true)));
    }

    @Test
    void theFirstConnectorStepIsRefusedWhenTheStarterIsNotTheOwner() {
        List<StepDef> steps = List.of(echo(1), connector(2), connector(3));

        assertThat(ConnectorStepOwnerGuard.firstRefusedStep(OTHER, AUTHOR, steps)).contains(2);
    }

    @Test
    void theOwnerIsNeverRefused() {
        assertThat(ConnectorStepOwnerGuard.firstRefusedStep(AUTHOR, AUTHOR, List.of(echo(1), connector(2)))).isEmpty();
    }

    @Test
    void onlyTheOwnerMayAuthorAConnectorStepAndTheStartRuleFollowsIt() {
        assertThat(ConnectorStepOwnerGuard.mayAuthor(AUTHOR, AUTHOR)).isTrue();
        assertThat(ConnectorStepOwnerGuard.mayAuthor(OTHER, AUTHOR)).isFalse();
        assertThat(ConnectorStepOwnerGuard.isConnectorCall("connector.call")).isTrue();
        assertThat(ConnectorStepOwnerGuard.isConnectorCall("echo")).isFalse();
        assertThat(ConnectorStepOwnerGuard.isConnectorCall(null)).isFalse();
    }

    @Test
    void aWorkflowWithoutConnectorStepsIsNeverRefused() {
        assertThat(ConnectorStepOwnerGuard.firstRefusedStep(OTHER, AUTHOR, List.of(echo(1), echo(2)))).isEmpty();
    }

    @Test
    void anotherMemberStartingAConnectorWorkflowIsRefusedNamingTheStep() {
        workflowOwnedBy(AUTHOR);

        assertThatThrownBy(() -> guard.check(OTHER, WORKFLOW, List.of(echo(1), connector(2)), CONTEXT))
                .isInstanceOfSatisfying(UnprocessableEntityException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("CONNECTOR_STEP_NOT_OWNER");
                    assertThat(e.getDetail().code()).isEqualTo("workflow.connectorStepNotOwner");
                    assertThat(english(e.getDetail()))
                            .isEqualTo("Step 2 calls a connected service, so only the workflow's owner can run it.");
                    assertThat(englishErrors(e)).containsExactly(Map.entry("step2",
                            "Step 2 calls a connected service, so only the workflow's owner can run it."));
                });
    }

    @Test
    void theOwnerStartsTheSameWorkflow() {
        workflowOwnedBy(AUTHOR);

        assertThatCode(() -> guard.check(AUTHOR, WORKFLOW, List.of(echo(1), connector(2)), CONTEXT))
                .doesNotThrowAnyException();
    }

    @Test
    void theWorkflowRowIsNotReadWhenNoStepCallsAConnector() {
        assertThatCode(() -> guard.check(OTHER, WORKFLOW, List.of(echo(1), echo(2)), CONTEXT))
                .doesNotThrowAnyException();

        verifyNoInteractions(workflows);
    }

    @Test
    void aMissingWorkflowRowIsLeftToTheStartFlow() {
        when(workflows.findById(WORKFLOW)).thenReturn(Optional.empty());

        assertThatCode(() -> guard.check(OTHER, WORKFLOW, List.of(connector(1)), CONTEXT))
                .doesNotThrowAnyException();
    }

    @Test
    void itRunsBeforeTheReadinessAndCreditGuards() {
        assertThat(ConnectorStepOwnerGuard.class.getAnnotation(Order.class).value()).isZero();

        CreditAccounts credits = mock(CreditAccounts.class);
        when(credits.enabled()).thenReturn(true);
        when(credits.balance(OTHER)).thenReturn(0L);
        RunStartGuards guards = new RunStartGuards(List.of(
                new CreditRunStartGuard(credits, mock(StepExecutorRegistry.class)),
                new WorkflowReadinessGuard(),
                guard));
        workflowOwnedBy(AUTHOR);
        List<WorkflowStep> steps = List.of(new WorkflowStep(UUID.randomUUID(), WORKFLOW, (short) 1, "Call",
                "connector.call", "[1]"));

        assertThatThrownBy(() -> guards.check(OTHER, WORKFLOW, steps, CONTEXT))
                .isInstanceOfSatisfying(UnprocessableEntityException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CONNECTOR_STEP_NOT_OWNER"));
    }

    private static Map<String, String> englishErrors(UnprocessableEntityException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getErrors().forEach((field, reason) -> errors.put(field, english(reason)));
        return errors;
    }
}
