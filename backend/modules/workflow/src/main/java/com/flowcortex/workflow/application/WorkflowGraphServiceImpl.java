package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.WorkflowGraphView;
import com.flowcortex.workflow.api.WorkflowGraphs;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementation of {@link WorkflowGraphs}. */
@Service
class WorkflowGraphServiceImpl implements WorkflowGraphs {

    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository workflowStepRepository;
    private final StepExecutorRegistry registry;

    WorkflowGraphServiceImpl(WorkflowRepository workflowRepository, WorkflowStepRepository workflowStepRepository,
            StepExecutorRegistry registry) {
        this.workflowRepository = workflowRepository;
        this.workflowStepRepository = workflowStepRepository;
        this.registry = registry;
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowGraphView graphOf(UUID callerId, UUID workflowId) {
        Workflow workflow = workflowRepository.findVisibleTo(workflowId, callerId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId)));
        return WorkflowGraph.project(workflow.getId(),
                workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId()), registry::inputPolicy,
                type -> WorkflowMapper.mode(type, registry::isRemote));
    }
}
