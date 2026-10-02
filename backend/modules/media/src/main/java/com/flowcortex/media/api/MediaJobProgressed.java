package com.flowcortex.media.api;

/** Published inside the media module's inbound transaction once a validated progress message arrives. */
public record MediaJobProgressed(MediaProgress progress) {
}
