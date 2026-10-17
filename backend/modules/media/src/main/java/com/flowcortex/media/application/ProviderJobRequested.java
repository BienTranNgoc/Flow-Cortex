package com.flowcortex.media.application;

import com.flowcortex.media.api.provider.ProviderJob;
import org.springframework.modulith.events.Externalized;

/** A provider job recorded transactionally before publication to RabbitMQ. */
@Externalized("provider::job")
public record ProviderJobRequested(ProviderJob job) {
}
