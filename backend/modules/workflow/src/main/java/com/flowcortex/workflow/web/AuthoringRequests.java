package com.flowcortex.workflow.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowcortex.workflow.api.NewStep;
import com.flowcortex.workflow.api.StepEdit;
import com.flowcortex.workflow.api.UpdateWorkflow;

/** Request-record to service-input mapping shared by the member and admin authoring controllers. */
final class AuthoringRequests {

    private AuthoringRequests() {
    }

    static String name(CreateWorkflowRequest request) {
        return request.name().trim();
    }

    static boolean shared(CreateWorkflowRequest request) {
        return Boolean.TRUE.equals(request.shared());
    }

    static UpdateWorkflow update(UpdateWorkflowRequest request) {
        return new UpdateWorkflow(request.name() == null ? null : request.name().trim(), request.shared(),
                request.status());
    }

    static NewStep newStep(CreateStepRequest request) {
        return new NewStep(request.name(), request.type(), request.inputRef(), configText(request.config()));
    }

    static StepEdit stepEdit(UpdateStepRequest request) {
        return new StepEdit(request.name(), configText(request.config()), request.inputRef());
    }

    /** An absent or JSON-null config means unchanged; anything else, objects or not, goes to the service's check. */
    private static String configText(JsonNode config) {
        return config == null || config.isNull() ? null : config.toString();
    }
}
