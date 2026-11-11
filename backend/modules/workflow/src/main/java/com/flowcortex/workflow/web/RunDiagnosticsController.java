package com.flowcortex.workflow.web;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.workflow.api.PeaksView;
import com.flowcortex.workflow.api.RunDiagnostics;
import com.flowcortex.workflow.api.RunLogView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/runs/{id}} diagnostics, always owner-scoped: a run's step log and the waveform of a sound one
 * of its steps stored. A run that is not the caller's, or was deleted, is a 404.
 */
@RestController
@RequestMapping("/api/v1/runs")
class RunDiagnosticsController {

    private static final Set<String> LEVELS = Set.of("error", "warn", "info", "all");
    private static final int MAX_LIMIT = 500;

    private final RunDiagnostics diagnostics;
    private final CurrentUserResolver currentUserResolver;

    RunDiagnosticsController(RunDiagnostics diagnostics, CurrentUserResolver currentUserResolver) {
        this.diagnostics = diagnostics;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(operationId = "getRunLog")
    @GetMapping(value = "/{id}/logs", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RunLogResponse> getLog(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Parameter(schema = @Schema(allowableValues = {"error", "warn", "info", "all"}, defaultValue = "all"))
            @RequestParam(defaultValue = "all") String level,
            @Parameter(schema = @Schema(minimum = "1", maximum = "500", defaultValue = "50"))
            @RequestParam(defaultValue = "50") int limit) {
        UUID currentUserId = currentUserResolver.require(jwt);
        if (!LEVELS.contains(level)) {
            throw new LocalizedStatusException(HttpStatus.BAD_REQUEST, new LocalizedMessage("workflow.runLog.level.invalid"));
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new LocalizedStatusException(HttpStatus.BAD_REQUEST, new LocalizedMessage("workflow.runLog.limit.invalid"));
        }
        RunLogView view = diagnostics.log(currentUserId, id, level, limit);
        return ResponseEntity.ok(RunLogResponse.from(view));
    }

    /** {@code peaks} is 120 numbers in 0..1, or {@code null} for a sound the server cannot decode. */
    @Operation(operationId = "getStepOutputPeaks")
    @GetMapping(value = "/{id}/steps/{order}/outputs/{name}/peaks", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PeaksResponse> getPeaks(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable int order, @PathVariable String name) {
        UUID currentUserId = currentUserResolver.require(jwt);
        PeaksView view = diagnostics.peaks(currentUserId, id, order, name);
        return ResponseEntity.ok(PeaksResponse.from(view));
    }
}
