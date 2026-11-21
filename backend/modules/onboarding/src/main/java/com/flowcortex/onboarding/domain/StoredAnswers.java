package com.flowcortex.onboarding.domain;

import java.util.List;

/** The answers already stored for a member; a null field is unanswered or skipped. */
public record StoredAnswers(
        List<String> purposes,
        String source,
        String aiLevel,
        List<String> industries,
        List<String> contentTypes,
        String usageType) {

    public static StoredAnswers empty() {
        return new StoredAnswers(null, null, null, null, null, null);
    }
}
