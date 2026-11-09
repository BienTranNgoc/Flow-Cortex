package com.flowcortex.llm.gemini;

import static com.flowcortex.llm.api.LlmJsonResult.Kind.QUOTA_EXCEEDED;
import static com.flowcortex.llm.api.LlmJsonResult.Kind.REJECTED;
import static com.flowcortex.llm.api.LlmJsonResult.Kind.TIMEOUT;
import static com.flowcortex.llm.api.LlmJsonResult.Kind.UNREACHABLE;
import static com.flowcortex.llm.api.LlmJsonResult.Kind.UNUSABLE;
import static com.flowcortex.llm.api.LlmJsonResult.Kind.UPSTREAM;
import static com.flowcortex.llm.gemini.GeminiChainPolicy.Mode.JSON;
import static com.flowcortex.llm.gemini.GeminiChainPolicy.Mode.SPEECH;
import static com.flowcortex.llm.gemini.GeminiChainPolicy.Verdict.NEXT_MODEL;
import static com.flowcortex.llm.gemini.GeminiChainPolicy.Verdict.RETRY_AFTER_WAIT;
import static com.flowcortex.llm.gemini.GeminiChainPolicy.Verdict.STOP;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.llm.api.LlmJsonResult.Kind;
import org.junit.jupiter.api.Test;

class GeminiChainPolicyTest {

    @Test
    void anAnswerOrABlockEndsTheCallInBothModes() {
        assertThat(GeminiChainPolicy.next(JSON, 1, 3, null)).isEqualTo(STOP);
        assertThat(GeminiChainPolicy.next(SPEECH, 1, 3, null)).isEqualTo(STOP);
    }

    @Test
    void anUnusableAnswerEndsTheCallInBothModes() {
        assertThat(GeminiChainPolicy.next(JSON, 1, 3, UNUSABLE)).isEqualTo(STOP);
        assertThat(GeminiChainPolicy.next(SPEECH, 1, 3, UNUSABLE)).isEqualTo(STOP);
    }

    @Test
    void quotaIsRetriedAfterAWaitInJsonModeAndMovesOnAtOnceInSpeechMode() {
        assertThat(GeminiChainPolicy.next(JSON, 1, 3, QUOTA_EXCEEDED)).isEqualTo(RETRY_AFTER_WAIT);
        assertThat(GeminiChainPolicy.next(JSON, 3, 3, QUOTA_EXCEEDED)).isEqualTo(NEXT_MODEL);
        assertThat(GeminiChainPolicy.next(SPEECH, 1, 3, QUOTA_EXCEEDED)).isEqualTo(NEXT_MODEL);
    }

    @Test
    void aRejectedRequestMovesOnInJsonModeAndEndsTheCallInSpeechMode() {
        assertThat(GeminiChainPolicy.next(JSON, 1, 3, REJECTED)).isEqualTo(NEXT_MODEL);
        assertThat(GeminiChainPolicy.next(SPEECH, 1, 3, REJECTED)).isEqualTo(STOP);
    }

    @Test
    void outagesAreRetriedAfterAWaitInBothModes() {
        for (Kind kind : new Kind[] {UPSTREAM, TIMEOUT, UNREACHABLE}) {
            assertThat(GeminiChainPolicy.next(JSON, 1, 3, kind)).isEqualTo(RETRY_AFTER_WAIT);
            assertThat(GeminiChainPolicy.next(JSON, 2, 3, kind)).isEqualTo(RETRY_AFTER_WAIT);
            assertThat(GeminiChainPolicy.next(SPEECH, 1, 3, kind)).isEqualTo(RETRY_AFTER_WAIT);
        }
    }

    @Test
    void usedUpAttemptsMoveToTheNextModel() {
        assertThat(GeminiChainPolicy.next(JSON, 3, 3, UPSTREAM)).isEqualTo(NEXT_MODEL);
        assertThat(GeminiChainPolicy.next(SPEECH, 3, 3, TIMEOUT)).isEqualTo(NEXT_MODEL);
        assertThat(GeminiChainPolicy.next(SPEECH, 1, 1, UPSTREAM)).isEqualTo(NEXT_MODEL);
        assertThat(GeminiChainPolicy.next(JSON, 1, 1, UNREACHABLE)).isEqualTo(NEXT_MODEL);
    }

    @Test
    void laterVoiceModelsGetOneAttemptAndEveryJsonModelGetsAll() {
        assertThat(GeminiChainPolicy.attemptsFor(SPEECH, 0, 3)).isEqualTo(3);
        assertThat(GeminiChainPolicy.attemptsFor(SPEECH, 1, 3)).isEqualTo(1);
        assertThat(GeminiChainPolicy.attemptsFor(JSON, 0, 3)).isEqualTo(3);
        assertThat(GeminiChainPolicy.attemptsFor(JSON, 1, 3)).isEqualTo(3);
    }

    @Test
    void anUnusableAnswerStaysUnusableWhateverQuotaWasRemembered() {
        // Speech: T 429, then L 200 without audio.
        assertThat(GeminiChainPolicy.finalKind(UNUSABLE, true)).isEqualTo(UNUSABLE);
        assertThat(GeminiChainPolicy.finalKind(UNUSABLE, false)).isEqualTo(UNUSABLE);
    }

    @Test
    void aRememberedQuotaOutranksTheLastFailure() {
        // Speech: T 429, then L 400. JSON: A 429 throughout, B 503 throughout.
        assertThat(GeminiChainPolicy.finalKind(REJECTED, true)).isEqualTo(QUOTA_EXCEEDED);
        assertThat(GeminiChainPolicy.finalKind(UPSTREAM, true)).isEqualTo(QUOTA_EXCEEDED);
    }

    @Test
    void withoutQuotaTheLastFailureIsTheFinalKind() {
        assertThat(GeminiChainPolicy.finalKind(REJECTED, false)).isEqualTo(REJECTED);
        assertThat(GeminiChainPolicy.finalKind(UPSTREAM, false)).isEqualTo(UPSTREAM);
        assertThat(GeminiChainPolicy.finalKind(TIMEOUT, false)).isEqualTo(TIMEOUT);
    }
}
