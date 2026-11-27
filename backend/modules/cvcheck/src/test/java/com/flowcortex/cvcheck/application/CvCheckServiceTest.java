package com.flowcortex.cvcheck.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.cvcheck.application.CvCheckService.Outcome;
import com.flowcortex.cvcheck.application.CvCheckService.Refusal;
import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.llm.api.LlmSlots;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CvCheckServiceTest {

    private static final String SAMPLE_CV = """
            Nguyen Van An - Java Backend Developer
            Email: an@example.com | Phone: 0912345678 | github.com/an-dev
            EXPERIENCE
            Software Engineer at ABC Corp (2022 - 2025). Built REST APIs with Java, Spring Boot, PostgreSQL and Redis.
            Improved API response latency by 50% and automated CI/CD pipelines with Docker and GitHub Actions.
            EDUCATION
            Bachelor of Information Technology - University of Technology.
            SKILLS
            Java, Spring Boot, PostgreSQL, Redis, Docker, Git.
            """;

    private static LlmSlots slots(LlmJsonResult result) {
        return new LlmSlots() {
            @Override
            public LlmJsonResult generateJson(String slot, String userText, String schema) {
                assertThat(slot).isEqualTo(CvCheckService.SLOT);
                assertThat(userText).contains("Nguyen Van An");
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
    void returnsTheModelReportForStandaloneCv() {
        Outcome outcome = new CvCheckService(slots(new LlmJsonResult.Answered("{\"isCv\":true,\"overallScore\":85}", "m", 1, 1)))
                .check(SAMPLE_CV);
        assertThat(outcome).isInstanceOf(Outcome.Report.class);
        assertThat(((Outcome.Report) outcome).report().get("overallScore").asInt()).isEqualTo(85);
    }

    @Test
    void returnsTheModelReportWithJd() {
        Outcome outcome = new CvCheckService(slots(new LlmJsonResult.Answered("{\"isCv\":true,\"overallScore\":80}", "m", 1, 1)))
                .check(SAMPLE_CV, "JD text");
        assertThat(outcome).isInstanceOf(Outcome.Report.class);
        assertThat(((Outcome.Report) outcome).report().get("overallScore").asInt()).isEqualTo(80);
    }

    @Test
    void rejectsNonCvFromValidityGateAndFromLlm() {
        Outcome gateRefused = new CvCheckService(slots(new LlmJsonResult.Answered("{}", "m", 1, 1)))
                .check("Invoice #999 Amount due 200 USD");
        assertThat(gateRefused).isEqualTo(new Outcome.Refused(Refusal.NOT_A_CV));

        Outcome llmRefused = new CvCheckService(slots(new LlmJsonResult.Answered(
                "{\"isCv\":false,\"notCvReason\":\"This is a job description.\",\"overallScore\":0}", "m", 1, 1)))
                .check(SAMPLE_CV);
        assertThat(llmRefused).isEqualTo(new Outcome.Refused(Refusal.NOT_A_CV, "This is a job description."));
    }

    @Test
    void anAnswerWithoutScoreIsUnavailable() {
        Outcome outcome = new CvCheckService(slots(new LlmJsonResult.Answered("{}", "m", 1, 1)))
                .check(SAMPLE_CV, "JD text");
        assertThat(outcome).isEqualTo(new Outcome.Refused(Refusal.UNAVAILABLE));
    }

    @Test
    void quotaIsBusyAndBlockIsBlocked() {
        assertThat(new CvCheckService(slots(new LlmJsonResult.Failed(LlmJsonResult.Kind.QUOTA_EXCEEDED)))
                .check(SAMPLE_CV, "JD text")).isEqualTo(new Outcome.Refused(Refusal.BUSY));
        assertThat(new CvCheckService(slots(new LlmJsonResult.Blocked("SAFETY")))
                .check(SAMPLE_CV, "JD text")).isEqualTo(new Outcome.Refused(Refusal.BLOCKED));
    }
}
