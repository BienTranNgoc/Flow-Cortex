package com.flowcortex.onboarding.domain;

import java.util.Map;

/** A body refused by a parser; {@link #errors()} maps each bad field to its message bundle key. */
public class InvalidOnboardingRequest extends RuntimeException {

    private final transient Map<String, String> errors;

    public InvalidOnboardingRequest(Map<String, String> errors) {
        super("invalid onboarding request: " + errors);
        this.errors = Map.copyOf(errors);
    }

    public InvalidOnboardingRequest(String field, String reasonKey) {
        this(Map.of(field, reasonKey));
    }

    public Map<String, String> errors() {
        return errors;
    }
}
