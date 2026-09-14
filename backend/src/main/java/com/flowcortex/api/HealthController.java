package com.flowcortex.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A tiny vertical slice so the skeleton boots with something to hit.
 *
 * <p>Package layout mirrors the reference repo's per-module layering:
 * {@code api} (controllers + wire DTOs) → {@code application} (use cases) →
 * {@code domain} (entities, rules) → {@code infrastructure} (adapters). Keep new
 * code on that gradient rather than one flat package.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", "flow-cortex");
    }

    public record HealthResponse(String status, String service) {}
}
