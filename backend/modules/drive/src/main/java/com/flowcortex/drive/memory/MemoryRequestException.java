package com.flowcortex.drive.memory;

import java.util.Map;

/** A Memory request whose query values were refused; {@code errors} maps each field to its message key. */
class MemoryRequestException extends RuntimeException {

    private final transient Map<String, String> errors;

    MemoryRequestException(Map<String, String> errors) {
        super("Memory query refused: " + errors.keySet());
        this.errors = Map.copyOf(errors);
    }

    Map<String, String> errors() {
        return errors;
    }
}
