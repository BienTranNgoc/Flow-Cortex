package com.flowcortex.cvcheck.application;

import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.llm.api.LlmSlots;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class CvCheckService {

    public static final String SLOT = "cv-check";
    public static final int MAX_CV_CHARS = 20_000;
    public static final int MAX_JD_CHARS = 10_000;

    private static final String STANDALONE_PROMPT = """
            You are a senior technical recruiter and executive resume reviewer applying the international
            Google XYZ · Harvard OCS · SHRM ATS evaluation standard (G-Eval anchored rubric).
            FIRST, determine whether the uploaded document is actually a candidate's CV / Resume.
            If it is NOT a CV/Resume (e.g. a job description/JD, invoice, receipt, textbook/lecture notes,
            research paper, contract, slide deck, or unrelated article), set isCv=false, explain briefly in
            notCvReason (1 concise sentence strictly in %s), and set all numeric scores to 0.
            Otherwise set isCv=true, notCvReason="", and evaluate this candidate's CV strictly using only
            evidence present in the CV; never invent facts or metrics.

            Score each dimension 0-100 using these anchored bands:
            - 90-100 (Exemplary / Tier 1): Concrete evidence exceeds top-tier benchmarks; strong metrics, zero fluff.
            - 75-89 (Proficient / Tier 2 - SHRM 75+ Benchmark): Solid, recruiter-ready, minor areas to sharpen.
            - 60-74 (Conditional / Tier 3): Acceptable baseline but lacks quantification, depth, or ATS polish.
            - 0-59 (Substandard / Tier 4): Passive task lists, missing critical structure, or major gaps.

            Dimensions & Standards:
            - experienceImpact (Weight 30%% — Google XYZ / STAR): Measurable outcomes
              ("Accomplished X as measured by Y, by doing Z"), scale, and strong action verbs.
              Penalize passive responsibility lists.
            - hardSkills (Weight 25%% — ESCO / O*NET): Technical depth, modern stack proficiency,
              and concrete proof of skills applied in projects/roles.
            - atsCompliance (Weight 20%% — SHRM / Enterprise ATS): Clean sectioning, parseable structure,
              contact completeness, and industry keyword density.
            - domainRelevance (Weight 15%% — Harvard OCS): Reverse-chronological clarity, consistent
              career progression, and credible professional positioning.
            - softSkills (Weight 10%% — SHRM / Harvard Tone): Ownership, collaboration, leadership signals,
              concise active-voice writing.

            overallScore: Weighted score (0-100) using
              0.30*experienceImpact + 0.25*hardSkills + 0.20*atsCompliance
              + 0.15*domainRelevance + 0.10*softSkills.
            OUTPUT LANGUAGE REQUIREMENT: Write ALL text fields (strengths, weaknesses, rewrittenBullets,
              interviewQuestions) strictly in %s.
            strengths: 3-5 concrete strong points backed by evidence in the CV.
            weaknesses: 3-5 specific weaknesses or gaps referencing the international standards above.
            missingKeywords: 4-8 important industry keywords or certifications that would elevate this CV.
            rewrittenBullets: Select up to 5 weak, passive, or vague bullets in the CV and rewrite them
              in Google XYZ / STAR style with strong action verbs (never fabricate fake numbers).
            interviewQuestions: 5-8 insightful technical and behavioral interview questions hiring
              managers will likely ask based on this CV.
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
            overallScore is the weighted judgement 0-100. Write ALL text fields strictly in %s.
            rewrittenBullets: rewrite up to 5 weak CV bullets in STAR style without adding facts.
            interviewQuestions: up to 8 questions the CV makes likely.
            jobProfile: extract primaryRole, seniority, topSkills (4-6 skills), and location from the CV.

            JOB DESCRIPTION:
            %s

            CV:
            %s
            """;

    private static final String SCHEMA = """
            {"type":"object","required":["isCv","notCvReason","overallScore","dimensions","strengths","weaknesses",
            "missingKeywords","rewrittenBullets","interviewQuestions","jobProfile"],"properties":{
            "isCv":{"type":"boolean"},
            "notCvReason":{"type":"string"},
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
        return check(cvText, null, 0, "vi");
    }

    public Outcome check(String cvText, String jobDescription) {
        return check(cvText, jobDescription, 0, "vi");
    }

    public Outcome check(String cvText, String jobDescription, int pageCount) {
        return check(cvText, jobDescription, pageCount, "vi");
    }

    public Outcome check(String cvText, String jobDescription, int pageCount, String lang) {
        if (!CvValidityGate.looksLikeCv(cvText, pageCount)) {
            return new Outcome.Refused(Refusal.NOT_A_CV);
        }
        String outputLang = CvDeterministicAuditor.isEnglish(lang)
                ? "English"
                : "Vietnamese (Tiếng Việt — even if the CV is written in English, every explanation, "
                        + "strength, weakness, rewritten bullet, and interview question MUST be in natural Vietnamese)";
        String prompt = (jobDescription == null || jobDescription.isBlank())
                ? STANDALONE_PROMPT.formatted(outputLang, outputLang, cvText)
                : JD_PROMPT.formatted(outputLang, jobDescription, cvText);
        LlmJsonResult result = llm.generateJson(SLOT, prompt, SCHEMA);
        return switch (result) {
            case LlmJsonResult.Answered answered -> parse(answered.json(), cvText, pageCount, lang);
            case LlmJsonResult.Blocked blocked -> new Outcome.Refused(Refusal.BLOCKED);
            case LlmJsonResult.Failed failed -> new Outcome.Refused(failed.kind() == LlmJsonResult.Kind.QUOTA_EXCEEDED
                    ? Refusal.BUSY : Refusal.UNAVAILABLE);
        };
    }

    private Outcome parse(String answer, String cvText, int pageCount, String lang) {
        try {
            JsonNode report = json.readTree(answer);
            if (report.has("isCv") && !report.path("isCv").asBoolean(true)) {
                String reason = report.path("notCvReason").asString("").strip();
                return new Outcome.Refused(Refusal.NOT_A_CV, reason.isEmpty() ? null : reason);
            }
            if (!report.hasNonNull("overallScore")) {
                return new Outcome.Refused(Refusal.UNAVAILABLE);
            }
            if (report instanceof ObjectNode obj && obj.path("dimensions").isObject()) {
                CvDeterministicAuditor.AuditResult audit = CvDeterministicAuditor.audit(cvText, pageCount, lang);
                CvScoringArbiter.arbitrate(obj, audit);
            }
            return new Outcome.Report(report);
        } catch (RuntimeException e) {
            return new Outcome.Refused(Refusal.UNAVAILABLE);
        }
    }

    public sealed interface Outcome permits Outcome.Report, Outcome.Refused {

        record Report(JsonNode report) implements Outcome {
        }

        record Refused(Refusal reason, String detail) implements Outcome {
            public Refused(Refusal reason) {
                this(reason, null);
            }
        }
    }

    public enum Refusal {
        NOT_A_CV, BLOCKED, BUSY, UNAVAILABLE
    }
}
