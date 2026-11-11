package com.flowcortex.workflow.api;

import java.util.UUID;

/** Read-only port for modules that need to know who a workflow is served to, without owning its tables. */
public interface WorkflowAudience {

    /** Never throws for a missing or deleted workflow: that is reported through {@link AudienceView#audience()}. */
    AudienceView describe(UUID workflowId);
}
