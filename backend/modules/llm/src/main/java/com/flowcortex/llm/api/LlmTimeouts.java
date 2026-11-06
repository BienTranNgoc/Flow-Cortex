package com.flowcortex.llm.api;

import java.time.Duration;

/** Time limits the configured provider puts on callers. An interface, because module beans are proxied. */
@FunctionalInterface
public interface LlmTimeouts {

    /** The longest one whole answer may take. */
    Duration answer();
}
