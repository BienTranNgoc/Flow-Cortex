package com.flowcortex.onboarding.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuizCompletionTest {

    private static final Instant EARLIER = Instant.parse("2026-11-10T09:00:00Z");

    private static final StoredAnswers CORE_STORED = new StoredAnswers(
            null, null, "tried", List.of("tech"), List.of("text"), null);

    private static QuizRequest request(Answer<String> usageType, boolean finished, boolean skipped) {
        return new QuizRequest(Answer.absent(), Answer.absent(), Answer.absent(), Answer.absent(), Answer.absent(),
                usageType, finished, skipped);
    }

    @Test
    void finishedStampsWhenNotYetComplete() {
        assertThat(QuizCompletion.completesNow(request(Answer.absent(), true, false), StoredAnswers.empty(), null))
                .isTrue();
    }

    @Test
    void allCoreAnsweredWithUsageTypeInRequestStamps() {
        assertThat(QuizCompletion.completesNow(request(Answer.value("team"), false, false), CORE_STORED, null))
                .isTrue();
    }

    @Test
    void allCoreStoredButNoUsageTypeInRequestDoesNotStamp() {
        StoredAnswers allStored = new StoredAnswers(null, null, "tried", List.of("tech"), List.of("text"), "team");
        QuizRequest purposesOnly = new QuizRequest(Answer.value(List.of("research")), Answer.absent(),
                Answer.absent(), Answer.absent(), Answer.absent(), Answer.absent(), false, false);

        assertThat(QuizCompletion.completesNow(purposesOnly, allStored, null)).isFalse();
    }

    @Test
    void skippedAnswerDoesNotCountAsAnswered() {
        QuizRequest skipsAiLevel = new QuizRequest(Answer.absent(), Answer.absent(), Answer.skipped(),
                Answer.absent(), Answer.absent(), Answer.value("team"), false, false);

        assertThat(QuizCompletion.completesNow(skipsAiLevel, CORE_STORED, null)).isFalse();
        assertThat(QuizCompletion.completesNow(request(Answer.value("team"), false, false),
                StoredAnswers.empty(), null)).isFalse();
    }

    @Test
    void alreadyCompletedNeverStampsAgain() {
        assertThat(QuizCompletion.completesNow(request(Answer.value("team"), true, true), CORE_STORED, EARLIER))
                .isFalse();
    }

    @Test
    void skippedRequestStampsAndChangesNoAnswer() {
        QuizRequest skipping = new QuizRequest(Answer.absent(), Answer.absent(), Answer.value("builder"),
                Answer.absent(), Answer.absent(), Answer.absent(), false, true);

        assertThat(QuizCompletion.completesNow(skipping, StoredAnswers.empty(), null)).isTrue();

        QuizRequest toWrite = QuizCompletion.answersToWrite(skipping);
        assertThat(toWrite.aiLevel()).isEqualTo(Answer.absent());
        assertThat(toWrite.skipped()).isTrue();
    }

    @Test
    void aRequestThatIsNotSkippedIsWrittenAsIs() {
        QuizRequest plain = request(Answer.value("team"), false, false);

        assertThat(QuizCompletion.answersToWrite(plain)).isSameAs(plain);
    }
}
