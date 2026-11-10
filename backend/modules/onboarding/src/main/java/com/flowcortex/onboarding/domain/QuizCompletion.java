package com.flowcortex.onboarding.domain;

import java.time.Instant;
import java.util.List;

/** When a quiz write stamps the member's {@code completedAt}, and which answers it may write. */
public final class QuizCompletion {

    private QuizCompletion() {
    }

    /**
     * True when this write must stamp {@code completedAt}: never when it is already set; always for
     * a skip or a finish; otherwise when the request carries usageType and the merged state answers
     * usageType, industries, aiLevel and contentTypes.
     */
    public static boolean completesNow(QuizRequest request, StoredAnswers stored, Instant previousCompletedAt) {
        if (previousCompletedAt != null) {
            return false;
        }
        if (request.skipped() || request.finished()) {
            return true;
        }
        return request.usageType() instanceof Answer.Value<String>
                && answered(request.usageType(), stored.usageType())
                && answered(request.industries(), stored.industries())
                && answered(request.aiLevel(), stored.aiLevel())
                && answered(request.contentTypes(), stored.contentTypes());
    }

    /** The request to persist: a skip changes no answer. */
    public static QuizRequest answersToWrite(QuizRequest request) {
        return request.skipped() ? request.withoutAnswers() : request;
    }

    private static boolean answered(Answer<String> incoming, String stored) {
        return switch (incoming) {
            case Answer.Value<String> v -> true;
            case Answer.Skipped<String> s -> false;
            case Answer.Absent<String> a -> stored != null;
        };
    }

    private static boolean answered(Answer<List<String>> incoming, List<String> stored) {
        return switch (incoming) {
            case Answer.Value<List<String>> v -> true;
            case Answer.Skipped<List<String>> s -> false;
            case Answer.Absent<List<String>> a -> stored != null && !stored.isEmpty();
        };
    }
}
