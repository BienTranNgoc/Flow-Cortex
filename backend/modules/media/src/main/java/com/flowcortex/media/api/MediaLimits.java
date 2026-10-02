package com.flowcortex.media.api;

import java.time.Duration;

/**
 * The configured bounds for one capability: how long a job may wait for pickup and run, and the
 * largest input object it may be given.
 */
public record MediaLimits(Duration queueTimeout, Duration maxRuntime, long maxInputBytes) {
}
