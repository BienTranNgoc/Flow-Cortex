package com.flowcortex.media.api;

import java.util.UUID;

/** Published inside the media module's inbound transaction once a validated vendor-task report arrives. */
public record MediaVendorTaskReported(VendorTaskReport report, UUID runId, UUID jobId) {
}
