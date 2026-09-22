package com.flowcortex.shared.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exists only so {@link GlobalExceptionHandlerTest} has real endpoints to
 * exercise the advice against (task 2.5) — never shipped, test sources only.
 */
@RestController
class ThrowawayTestController {

    @GetMapping("/test/not-found")
    String triggerNotFound() {
        throw new NotFoundException("thing not found");
    }

    @GetMapping("/test/conflict")
    String triggerConflict() {
        throw new ConflictException("thing conflicts");
    }

    @PostMapping("/test/validate")
    String triggerValidation(@Valid @RequestBody ThrowawayRequest request) {
        return "ok";
    }

    record ThrowawayRequest(@NotBlank String name) {
    }
}
