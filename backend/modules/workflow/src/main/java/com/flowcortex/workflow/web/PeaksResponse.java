package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.PeaksView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** {@code peaks} holds 120 numbers in 0..1, or is {@code null} when the server cannot decode the sound. */
record PeaksResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, minimum = "0", maximum = "1") List<Double> peaks) {

    static PeaksResponse from(PeaksView view) {
        return new PeaksResponse(view.peaks());
    }
}
