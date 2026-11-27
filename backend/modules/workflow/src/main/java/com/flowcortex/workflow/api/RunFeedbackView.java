package com.flowcortex.workflow.api;

import java.time.Instant;

/**
 * The owner's verdict on a run: {@code up} or {@code down}; {@code issue} is set for {@code down} only and
 * {@code detail} is the optional free text, {@code null} when none was given.
 */
public record RunFeedbackView(String verdict, String issue, String detail, Instant updatedAt) {
}
