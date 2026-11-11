package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.RunStartGuard;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Refuses a run of another member's workflow that holds a connector call step: the run would send the
 * author's chosen request with the starter's connected credentials. A workflow row that no longer exists is left
 * to the start flow.
 */
@Component
@Order(0)
class ConnectorStepOwnerGuard implements RunStartGuard {

    static final String CODE = "CONNECTOR_STEP_NOT_OWNER";

    private final WorkflowRepository workflows;

    ConnectorStepOwnerGuard(WorkflowRepository workflows) {
        this.workflows = workflows;
    }

    @Override
    public void check(UUID ownerId, UUID workflowId, List<StepDef> steps, StartContext context) {
        if (steps.stream().noneMatch(ConnectorStepOwnerGuard::callsConnector)) {
            return;
        }
        Optional<UUID> author = workflows.findById(workflowId).map(Workflow::getOwnerId);
        if (author.isEmpty()) {
            return;
        }
        Optional<Integer> refused = firstRefusedStep(ownerId, author.get(), steps);
        if (refused.isPresent()) {
            LocalizedMessage message = new LocalizedMessage("workflow.connectorStepNotOwner", refused.get());
            throw new UnprocessableEntityException(message, CODE, Map.of("step" + refused.get(), message));
        }
    }

    /** The order of the first connector call step when the starter is not the workflow's owner. */
    static Optional<Integer> firstRefusedStep(UUID starter, UUID workflowOwner, List<StepDef> steps) {
        if (mayAuthor(starter, workflowOwner)) {
            return Optional.empty();
        }
        return steps.stream().filter(ConnectorStepOwnerGuard::callsConnector).map(StepDef::order).findFirst();
    }

    /**
     * Whether {@code person} may author a connector call step on, or run one from, a workflow owned by
     * {@code workflowOwner}: the one whose credential sends the request is the one who chose it, so only the owner.
     */
    static boolean mayAuthor(UUID person, UUID workflowOwner) {
        return person.equals(workflowOwner);
    }

    static boolean isConnectorCall(String stepType) {
        return ConnectorCallStepExecutor.TYPE.equals(stepType);
    }

    private static boolean callsConnector(StepDef step) {
        return isConnectorCall(step.type());
    }
}
