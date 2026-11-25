package com.flowcortex.cvcheck.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.cvcheck.application.CvCheckService.Outcome;
import com.flowcortex.cvcheck.application.CvCheckService.Refusal;
import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.llm.api.LlmSlots;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CvCheckServiceTest {

    private static LlmSlots slots(LlmJsonResult result) {
        return new LlmSlots() {
            @Override
            public LlmJsonResult generateJson(String slot, String userText, String schema) {
                assertThat(slot).isEqualTo(CvCheckService.SLOT);
                assertThat(userText).contains("JD text").contains("CV text");
                return result;
            }

            @Override
            public com.flowcortex.llm.api.LlmSpeechResult speak(String slot, String text, String voice) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Duration jsonWorstCase(String slot) {
                return Duration.ZERO;
            }

            @Override
            public Duration speechWorstCase(String slot) {
                return Duration.ZERO;
            }

            @Override
            public boolean configured() {
                return true;
            }
        };
    }

    @Test
    void returnsTheModelReport() {
        Outcome outcome = new CvCheckService(slots(new LlmJsonResult.Answered("{\"overallScore\":80}", "m", 1, 1)))
                .check("CV text", "JD text");
        assertThat(outcome).isInstanceOf(Outcome.Report.class);
        assertThat(((Outcome.Report) outcome).report().get("overallScore").asInt()).isEqualTo(80);
    }

    @Test
    void anAnswerWithoutScoreIsUnavailable() {
        Outcome outcome = new CvCheckService(slots(new LlmJsonResult.Answered("{}", "m", 1, 1)))
                .check("CV text", "JD text");
        assertThat(outcome).isEqualTo(new Outcome.Refused(Refusal.UNAVAILABLE));
    }

    @Test
    void quotaIsBusyAndBlockIsBlocked() {
        assertThat(new CvCheckService(slots(new LlmJsonResult.Failed(LlmJsonResult.Kind.QUOTA_EXCEEDED)))
                .check("CV text", "JD text")).isEqualTo(new Outcome.Refused(Refusal.BUSY));
        assertThat(new CvCheckService(slots(new LlmJsonResult.Blocked("SAFETY")))
                .check("CV text", "JD text")).isEqualTo(new Outcome.Refused(Refusal.BLOCKED));
    }
}
