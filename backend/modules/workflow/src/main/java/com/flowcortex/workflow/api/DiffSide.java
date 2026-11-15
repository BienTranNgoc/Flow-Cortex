package com.flowcortex.workflow.api;

/**
 * One resolved side of a comparison: {@code ref} is {@code draft}, {@code published} or the requested version
 * number as text; {@code version} is the version number it resolved to, null for the live draft.
 */
public record DiffSide(String ref, Integer version) {
}
