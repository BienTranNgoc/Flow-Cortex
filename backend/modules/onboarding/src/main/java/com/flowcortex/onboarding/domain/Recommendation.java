package com.flowcortex.onboarding.domain;

/** The track key the member should start on, with the answers it was derived from. */
public record Recommendation(String industry, String track, String usageType) {
}
