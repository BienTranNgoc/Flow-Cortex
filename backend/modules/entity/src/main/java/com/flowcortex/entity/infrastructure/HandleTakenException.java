package com.flowcortex.entity.infrastructure;

/** A write lost the unique handle of live entities to a concurrent create; nothing of it was kept. */
public class HandleTakenException extends RuntimeException {

    public HandleTakenException(Throwable cause) {
        super("Entity handle is held by another live entity", cause);
    }
}
