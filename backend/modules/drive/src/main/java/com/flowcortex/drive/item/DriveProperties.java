package com.flowcortex.drive.item;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bound from {@code flowcortex.drive.*} ({@code application.yml} carries the defaults). Uploaded files never
 * expire, so each space is capped: a create that would pass either limit is refused.
 */
@Validated
@ConfigurationProperties("flowcortex.drive")
record DriveProperties(@Positive long maxBytesPerSpace, @Positive long maxItemsPerSpace) {
}
