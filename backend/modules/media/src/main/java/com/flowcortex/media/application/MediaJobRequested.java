package com.flowcortex.media.application;

import com.flowcortex.media.api.MediaJob;
import org.springframework.modulith.events.Externalized;

/**
 * The outbox event for one job: recorded in the event publication registry with the caller's
 * transaction and externalized to exchange {@code media} after commit, with the routing key of the job's
 * capability lane instead of the annotation's {@code job}.
 */
@Externalized("media::job")
public record MediaJobRequested(MediaJob job) {
}
