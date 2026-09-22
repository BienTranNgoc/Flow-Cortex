package com.flowcortex.app.health;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A tiny vertical slice so the skeleton boots with something to hit.
 *
 * <p>Lives under {@code com.flowcortex.app} — the composition root's own
 * package, not a business module — so it stays out of the way of the
 * {@code user}/{@code workflow} module boundaries Spring Modulith enforces.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    // Explicit `produces` (task 3.1/3.2 finding): without it, springdoc has no
    // fixed content-type to document and emits the ambiguous wildcard `*/*`
    // for this operation's response, which made orval's generated fetch
    // client fall back to an untyped raw-string body — a real
    // client.typecheck failure, not merely a documentation nicety.
    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public HealthResponse health() {
        return new HealthResponse("UP", "flow-cortex");
    }

    // @Schema on a record component (design.md D6): the smoke test for
    // springdoc's early-Boot-4/Jackson-3 introspection friction — proven by
    // OpenApiSnapshotTest asserting these descriptions actually reach the
    // generated /v3/api-docs document, not merely compiling.
    public record HealthResponse(
            @Schema(description = "Overall service health status.", example = "UP") String status,
            @Schema(description = "Identifier of the service reporting its health.", example = "flow-cortex")
                    String service) {}
}
