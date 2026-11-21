package com.flowcortex.onboarding.domain;

import java.util.List;

/**
 * The allowed values of every onboarding answer and progress field. The member app mirrors these
 * lists in {@code onboardingVocabulary.ts}; this class is the source of truth, update both together.
 */
public final class OnboardingVocabulary {

    public static final List<String> PURPOSES = List.of(
            "marketing_content", "automation", "documents_reports", "customer_care", "research", "other");
    public static final List<String> SOURCES = List.of("facebook", "tiktok", "linkedin", "google", "referral", "other");
    public static final List<String> AI_LEVELS = List.of("beginner", "tried", "automation", "builder");
    /** In storage order: {@code other} is always last. */
    public static final List<String> INDUSTRIES = List.of("marketing", "sales", "finance", "hr", "tech", "other");
    public static final List<String> CONTENT_TYPES = List.of(
            "text", "image", "audio", "video", "spreadsheet", "document", "slide");
    public static final List<String> USAGE_TYPES = List.of("personal", "team");
    public static final List<String> STEPS = List.of("welcome", "tour:home", "survey-intro", "quiz", "done");
    public static final List<String> BUILDER_REASONS = List.of("skipped", "finished");

    public static final int MIN_QUESTION = 1;
    public static final int MAX_QUESTION = 6;

    private OnboardingVocabulary() {
    }
}
