package com.flowcortex.cvcheck.application;

import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.llm.api.LlmSlots;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class CvCheckService {

    public static final String SLOT = "cv-check";
    public static final int MAX_CV_CHARS = 20_000;
    public static final int MAX_JD_CHARS = 10_000;

    private static final String PROMPT = """
            You are a senior technical recruiter and CV coach. Evaluate the CV against the job description.
            Score each dimension 0-100 using only evidence in the CV; never invent experience.
            Dimensions: hardSkills (match of core skills), experienceImpact (measurable results, STAR/XYZ),
            softSkills, atsCompliance (structure, keywords, formatting), domainRelevance.
            overallScore is the weighted judgement 0-100. Write all text in the language of the CV.
            rewrittenBullets: rewrite up to 5 weak CV bullets in STAR style without adding facts.
            interviewQuestions: up to 8 questions the CV makes likely.

            JOB DESCRIPTION:
            %s

            CV:
            %s
            """;

    private static final String SCHEMA = """
            {"type":"object","required":["overallScore","dimensions","strengths","weaknesses","missingKeywords",
            "rewrittenBullets","interviewQuestions"],"properties":{
            "overallScore":{"type":"integer"},
            "dimensions":{"type":"object","required":["hardSkills","experienceImpact","softSkills","atsCompliance",
            "domainRelevance"],"properties":{"hardSkills":{"type":"integer"},"experienceImpact":{"type":"integer"},
            "softSkills":{"type":"integer"},"atsCompliance":{"type":"integer"},"domainRelevance":{"type":"integer"}}},
            "strengths":{"type":"array","items":{"type":"string"}},
            "weaknesses":{"type":"array","items":{"type":"string"}},
            "missingKeywords":{"type":"array","items":{"type":"string"}},
            "rewrittenBullets":{"type":"array","items":{"type":"object","required":["original","improved"],
            "properties":{"original":{"type":"string"},"improved":{"type":"string"}}}},
            "interviewQuestions":{"type":"array","items":{"type":"string"}}}}
            """;

    private final LlmSlots llm;
    private final ObjectMapper json = new ObjectMapper();

    CvCheckService(LlmSlots llm) {
        this.llm = llm;
    }

    public Outcome check(String cvText, String jobDescription) {
        LlmJsonResult result = llm.generateJson(SLOT, PROMPT.formatted(jobDescription, cvText), SCHEMA);
        return switch (result) {
            case LlmJsonResult.Answered answered -> parse(answered.json());
            case LlmJsonResult.Blocked blocked -> new Outcome.Refused(Refusal.BLOCKED);
            case LlmJsonResult.Failed failed -> new Outcome.Refused(failed.kind() == LlmJsonResult.Kind.QUOTA_EXCEEDED
                    ? Refusal.BUSY : Refusal.UNAVAILABLE);
        };
    }

    private Outcome parse(String answer) {
        try {
            JsonNode report = json.readTree(answer);
            return report.hasNonNull("overallScore") ? new Outcome.Report(report)
                    : new Outcome.Refused(Refusal.UNAVAILABLE);
        } catch (RuntimeException e) {
            return new Outcome.Refused(Refusal.UNAVAILABLE);
        }
    }

    public sealed interface Outcome permits Outcome.Report, Outcome.Refused {

        record Report(JsonNode report) implements Outcome {
        }

        record Refused(Refusal reason) implements Outcome {
        }
    }

    public enum Refusal {
        BLOCKED, BUSY, UNAVAILABLE
    }
}
