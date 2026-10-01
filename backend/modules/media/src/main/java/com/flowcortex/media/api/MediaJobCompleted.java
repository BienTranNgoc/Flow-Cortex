package com.flowcortex.media.api;

/** Published inside the media module's inbound transaction once a validated result arrives. */
public record MediaJobCompleted(MediaResult result) {
}
