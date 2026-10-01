package com.flowcortex.media.infrastructure;

import com.flowcortex.media.application.MediaProperties;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/** {@code mediaStore} in the health endpoint: UP while the media bucket answers a HeadBucket. */
@Component
class MediaStoreHealthIndicator extends AbstractHealthIndicator {

    private final S3MediaStore store;
    private final String bucket;

    MediaStoreHealthIndicator(S3MediaStore store, MediaProperties properties) {
        super("The object store could not be reached");
        this.store = store;
        this.bucket = properties.s3().bucket();
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        store.headBucket(bucket);
        builder.up().withDetail("bucket", bucket);
    }
}
