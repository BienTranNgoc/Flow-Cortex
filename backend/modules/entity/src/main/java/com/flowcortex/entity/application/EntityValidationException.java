package com.flowcortex.entity.application;

import java.util.Map;

/** A request broke an entity rule; {@code errors} maps each offending field to the bundle key of its sentence. */
public class EntityValidationException extends RuntimeException {

    private final transient Map<String, String> errors;

    public EntityValidationException(Map<String, String> errors) {
        super("Entity validation failed: " + errors);
        this.errors = Map.copyOf(errors);
    }

    public Map<String, String> errors() {
        return errors;
    }
}
