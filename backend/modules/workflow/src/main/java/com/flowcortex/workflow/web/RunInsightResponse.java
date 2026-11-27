package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.RunInsightView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** The written reading of a table result: a headline and up to three highlights. */
record RunInsightResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) String headline,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<String> highlights) {

    static RunInsightResponse from(RunInsightView view) {
        return view == null ? null : new RunInsightResponse(view.headline(), view.highlights());
    }
}
