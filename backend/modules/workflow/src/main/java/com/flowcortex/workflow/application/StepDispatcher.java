package com.flowcortex.workflow.application;

import org.springframework.stereotype.Component;

/**
 * Executes a step's work by {@code type} — only two MVP types; a real provider catalogue is
 * a later change.
 *
 * <ul>
 *   <li>{@code echo} — returns resolved input verbatim (proves the engine without a provider).</li>
 *   <li>{@code fail} — deterministically throws (exercises the run-error UI from a demo).</li>
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
