package com.flowcortex.media.api.provider;

import jakarta.validation.constraints.NotEmpty;

/**
 * A reference to an object already in the store. The provider worker fetches it via
 * {@link com.flowcortex.media.api.MediaJobs}, never by URL; the producer is responsible for
 * copying the object into the worker's bucket before the job is published.
 */
public record ProviderInput(@NotEmpty String key, @NotEmpty String bucket, @NotEmpty String objectKey) {
}
