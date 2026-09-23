package com.flowcortex.app.health;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A tiny vertical slice so the skeleton boots with something to hit. Lives under {@code
 * com.flowcortex.app} (composition root, not a business module) to stay out of Spring
 * Modulith's module boundaries.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    // Explicit produces: without it springdoc emits */* and orval generates an untyped body.
    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public HealthResponse health() {
        return new HealthResponse("UP", "flow-cortex");
    }

    // @Schema on a record component: smoke-tests springdoc's Boot-4/Jackson-3 introspection,
    // asserted by OpenApiSnapshotTest against /v3/api-docs.
    public record HealthResponse(
            @Schema(description = "Overall service health status.", example = "UP") String status,
            @Schema(description = "Identifier of the service reporting its health.", example = "flow-cortex")
                    String service) {}
}
