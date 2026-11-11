package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.AudienceView;
import com.flowcortex.workflow.api.AudienceView.Audience;
import com.flowcortex.workflow.api.AudienceView.StartKind;
import com.flowcortex.workflow.api.WorkflowAudience;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStatus;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class WorkflowAudienceImpl implements WorkflowAudience {

    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository workflowStepRepository;
    private final StepExecutorRegistry registry;

    WorkflowAudienceImpl(WorkflowRepository workflowRepository, WorkflowStepRepository workflowStepRepository,
            StepExecutorRegistry registry) {
        this.workflowRepository = workflowRepository;
        this.workflowStepRepository = workflowStepRepository;
        this.registry = registry;
    }

    @Override
    @Transactional(readOnly = true)
    public AudienceView describe(UUID workflowId) {
        Workflow workflow = workflowRepository.findById(workflowId).orElse(null);
        if (workflow == null) {
            return new AudienceView(Audience.NOT_FOUND, null, null);
        }
        WorkflowView view = WorkflowMapper.toWorkflowView(workflow,
                workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId), registry::isRemote,
                registry::isAvailable);
        return new AudienceView(audienceOf(workflow), view, startKindOf(view));
    }

    private static Audience audienceOf(Workflow workflow) {
        if (workflow.getDeletedAt() != null) {
            return Audience.DELETED;
        }
        if (workflow.getStatus() == WorkflowStatus.DRAFT) {
            return Audience.DRAFT;
        }
        return workflow.isShared() ? Audience.ALL_MEMBERS : Audience.OWNER_ONLY;
    }

    /** A first step that runs on the media engine reads an uploaded file; every other first step reads text. */
    private static StartKind startKindOf(WorkflowView view) {
        boolean remoteFirst = !view.steps().isEmpty() && "remote".equals(view.steps().get(0).mode());
        return remoteFirst ? StartKind.UPLOAD : StartKind.TEXT;
    }
}
