package com.flowcortex.workflow.api;

/** One object a step produced, by name; where it is stored stays server-side. */
public record RunStepOutputView(String name, String mime, long bytes) {
}
