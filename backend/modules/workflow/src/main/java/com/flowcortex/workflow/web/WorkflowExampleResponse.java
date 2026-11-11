package com.flowcortex.workflow.web;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.application.WorkflowExamples;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/**
 * One example workflow in the request language. {@code sampleInput} is {@code null} when the run starts from an
 * upload; {@code estimatedCredits} is {@code null} while credits are switched off.
 */
record WorkflowExampleResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) String id,
        @Schema(requiredMode = RequiredMode.REQUIRED) String pattern,
        @Schema(requiredMode = RequiredMode.REQUIRED) String title,
        @Schema(requiredMode = RequiredMode.REQUIRED) String description,
        String sampleInput,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<String> stepTypes,
        @Schema(requiredMode = RequiredMode.REQUIRED) int stepCount,
        Long estimatedCredits) {

    static WorkflowExampleResponse from(WorkflowExamples.Example example, CreditAccounts credits) {
        return new WorkflowExampleResponse(example.id(), example.pattern(), example.title(),
                example.description(), example.sampleInput(), example.stepTypes(), example.stepCount(),
                credits.enabled() ? credits.estimate(example.stepTypes()) : null);
    }
}
