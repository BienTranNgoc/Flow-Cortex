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

    private static final String STANDALONE_PROMPT = """
            You are a senior technical recruiter, executive resume reviewer and CV coach.
            Evaluate this candidate's CV thoroughly on its own merits to help them improve it.
            Score each dimension 0-100 using only evidence in the CV; never invent experience:
            - hardSkills: Technical depth, clarity, and industry-standard proficiency of the skill stack.
            - experienceImpact: Action-oriented achievements, measurable business/technical outcomes, and strong use of metrics (STAR/XYZ format).
            - softSkills: Leadership, collaboration, communication, and ownership evident in project and job descriptions.
            - atsCompliance: ATS-friendliness, formatting clarity, clean sectioning, professional contact info, and industry keyword density.
            - domainRelevance: Career progression consistency, credibility, and overall professional presentation.

            overallScore: Weighted overall assessment (0-100).
            Write all text in the language of the CV (e.g. Vietnamese if the CV is in Vietnamese, English if in English).
            strengths: 3-5 outstanding strong points of this CV.
            weaknesses: 3-5 weaknesses, vague descriptions, or gaps that should be improved.
            missingKeywords: 4-8 important industry keywords or certifications that would elevate this CV.
            rewrittenBullets: Select up to 5 weak, passive, or vague bullets in the CV and rewrite them in punchy STAR style with strong action verbs (without fabricating facts).
            interviewQuestions: 5-8 insightful technical and behavioral interview questions hiring managers will likely ask based on this CV.
            jobProfile: Extract the candidate's target job search profile from the CV:
            - primaryRole: Concise standard job title (e.g. "Java Backend Developer", "Frontend React Engineer").
            - seniority: Estimated level ("Intern", "Fresher", "Junior", "Middle", "Senior", or "Lead").
            - topSkills: 4-6 strongest concrete technical or domain skills from the CV.
            - location: City or country inferred from the CV (default to "Vietnam" if unspecified).

            CANDIDATE CV:
            %s
            """;

    private static final String JD_PROMPT = """
            You are a senior technical recruiter and CV coach. Evaluate the CV against the job description.
            Score each dimension 0-100 using only evidence in the CV; never invent experience.
            Dimensions: hardSkills (match of core skills), experienceImpact (measurable results, STAR/XYZ),
            softSkills, atsCompliance (structure, keywords, formatting), domainRelevance.
            overallScore is the weighted judgement 0-100. Write all text in the language of the CV.
            rewrittenBullets: rewrite up to 5 weak CV bullets in STAR style without adding facts.
            interviewQuestions: up to 8 questions the CV makes likely.
            jobProfile: extract primaryRole, seniority, topSkills (4-6 skills), and location from the CV.

            JOB DESCRIPTION:
            %s

            CV:
            %s
            """;

    private static final String SCHEMA = """
            {"type":"object","required":["overallScore","dimensions","strengths","weaknesses","missingKeywords",
            "rewrittenBullets","interviewQuestions","jobProfile"],"properties":{
            "overallScore":{"type":"integer"},
            "dimensions":{"type":"object","required":["hardSkills","experienceImpact","softSkills","atsCompliance",
            "domainRelevance"],"properties":{"hardSkills":{"type":"integer"},"experienceImpact":{"type":"integer"},
            "softSkills":{"type":"integer"},"atsCompliance":{"type":"integer"},"domainRelevance":{"type":"integer"}}},
            "strengths":{"type":"array","items":{"type":"string"}},
            "weaknesses":{"type":"array","items":{"type":"string"}},
            "missingKeywords":{"type":"array","items":{"type":"string"}},
            "rewrittenBullets":{"type":"array","items":{"type":"object","required":["original","improved"],
            "properties":{"original":{"type":"string"},"improved":{"type":"string"}}}},
            "interviewQuestions":{"type":"array","items":{"type":"string"}},
            "jobProfile":{"type":"object","required":["primaryRole","seniority","topSkills","location"],
            "properties":{"primaryRole":{"type":"string"},"seniority":{"type":"string"},
            "topSkills":{"type":"array","items":{"type":"string"}},"location":{"type":"string"}}}}}
            """;

    private final LlmSlots llm;
    private final ObjectMapper json = new ObjectMapper();

    CvCheckService(LlmSlots llm) {
        this.llm = llm;
    }

    public Outcome check(String cvText) {
        return check(cvText, null);
    }

    public Outcome check(String cvText, String jobDescription) {
        String prompt = (jobDescription == null || jobDescription.isBlank())
                ? STANDALONE_PROMPT.formatted(cvText)
                : JD_PROMPT.formatted(jobDescription, cvText);
        LlmJsonResult result = llm.generateJson(SLOT, prompt, SCHEMA);
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
