package com.flowcortex.workflow.application;

import org.springframework.stereotype.Component;

/**
 * Executes a single step's work by {@code type} (design.md D1 Open
 * Questions — only two MVP types exist; a real provider-backed catalogue is
 * a later change).
 *
 * <ul>
 *   <li>{@code echo} — returns its resolved input verbatim (R-042's "engine
 *   is provable without wiring an external provider").</li>
 *   <li>{@code fail} — deliberately, deterministically throws, so the
 *   run-error UI (J-11) is reachable from a seeded demo (task 5.9) without a
 *   real failing provider.</li>
 * </ul>
 */
@Component
class StepDispatcher {

    String execute(String type, String resolvedInput) {
        return switch (type) {
            case "echo" -> resolvedInput;
            case "fail" -> throw new IllegalStateException("Simulated step failure for demo purposes");
            default -> throw new IllegalStateException("Unsupported step type: " + type);
        };
    }
}
