package com.flowcortex.workflow.application;

import java.util.UUID;

/** Who is writing: the user id and whether the entry point verified the admin role. */
public record Caller(UUID userId, boolean admin) {

    public static Caller member(UUID userId) {
        return new Caller(userId, false);
    }

    public static Caller admin(UUID userId) {
        return new Caller(userId, true);
    }
}
