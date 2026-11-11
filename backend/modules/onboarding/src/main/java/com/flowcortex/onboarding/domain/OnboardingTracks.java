package com.flowcortex.onboarding.domain;

import java.util.List;
import java.util.Map;

/** Maps a member's stored answers to the recommended track key. */
public final class OnboardingTracks {

    private static final String OTHER = "other";
    private static final String DEFAULT_USAGE_TYPE = "personal";

    private static final Map<String, String> TRACK_BY_INDUSTRY = Map.of(
            "marketing", "marketing",
            "sales", "sales",
            "finance", "finance",
            "hr", "hr",
            "tech", "tech",
            OTHER, OTHER);

    private OnboardingTracks() {
    }

    /** The first stored industry (else {@code other}), its track, and the usage type (else {@code personal}). */
    public static Recommendation of(List<String> industries, String usageType) {
        String industry = industries == null || industries.isEmpty() ? OTHER : industries.get(0);
        return new Recommendation(
                industry,
                TRACK_BY_INDUSTRY.getOrDefault(industry, OTHER),
                usageType == null ? DEFAULT_USAGE_TYPE : usageType);
    }
}
