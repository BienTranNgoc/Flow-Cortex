package com.flowcortex.drive.item;

import java.util.Map;

/** A Drive request broke a rule; {@code errors} maps each offending field to the bundle key of its sentence. */
class DriveValidationException extends RuntimeException {

    private final transient Map<String, String> errors;

    DriveValidationException(Map<String, String> errors) {
        super("Drive validation failed: " + errors);
        this.errors = Map.copyOf(errors);
    }

    Map<String, String> errors() {
        return errors;
    }
}
