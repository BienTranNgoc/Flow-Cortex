package com.flowcortex.workflow.api;

/**
 * A step to append to an existing workflow. {@code config}, when set, is the serialized JSON text the caller sent;
 * the service refuses anything that is not a JSON object.
 */
public record NewStep(String name, String type, String inputRef, String config) {
}
