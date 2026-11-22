package com.flowcortex.user.api;

import java.time.Duration;

/**
 * The closed list of actions that demand a fresh password check, each with how long its proof
 * lives. A purpose is added together with the action that consumes it. The stored column is
 * {@code varchar(40)}.
 */
public enum StepUpPurpose {

    PAT_CREATE(Duration.ofSeconds(300)),
    PHONE_CHANGE(Duration.ofSeconds(300));

    private final Duration ttl;

    StepUpPurpose(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration ttl() {
        return ttl;
    }
}
