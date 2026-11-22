package com.flowcortex.drive.suggestion;

import com.flowcortex.drive.web.DriveCaller;
import com.flowcortex.drive.web.DriveCaller.Caller;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/drive/suggestions} — what the caller's recent completed runs offer to keep in Drive. */
@RestController
@RequestMapping("/api/v1/drive/suggestions")
class SuggestionsController {

    private static final int MAX_LIMIT = 50;

    private final DriveCaller driveCaller;
    private final SuggestionsService service;

    SuggestionsController(DriveCaller driveCaller, SuggestionsService service) {
        this.driveCaller = driveCaller;
        this.service = service;
    }

    @Operation(operationId = "listDriveSuggestions")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<List<SuggestionResponse>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_LIMIT) int limit) {
        Caller caller = driveCaller.require(jwt);
        return ResponseEntity.ok(service.list(caller.userId(), limit).stream().map(SuggestionResponse::from).toList());
    }
}
