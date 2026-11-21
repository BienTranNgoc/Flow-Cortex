package com.flowcortex.user.audit;

/** The acting admin's email and name; both null when the lookup found no row or failed. */
public record ActorSnapshot(String email, String name) {

    public static final ActorSnapshot UNKNOWN = new ActorSnapshot(null, null);
}
