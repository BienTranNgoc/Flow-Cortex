package com.flowcortex.cvcheck.application;

import com.flowcortex.cvcheck.application.CvDeterministicAuditor.AtsCheck;
import com.flowcortex.cvcheck.application.CvDeterministicAuditor.AuditResult;
import com.flowcortex.cvcheck.application.CvDeterministicAuditor.Status;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class CvScoringArbiter {

    public static final String RUBRIC_ID = "google-xyz-harvard-shrm-v1";
    public static final String RUBRIC_NAME = "Google XYZ · Harvard OCS · SHRM ATS";

    public static final int WEIGHT_IMPACT = 30;
    public static final int WEIGHT_HARD_SKILLS = 25;
    public static final int WEIGHT_ATS = 20;
    public static final int WEIGHT_CLARITY = 15;
    public static final int WEIGHT_SOFT_SKILLS = 10;

    private static final int NO_METRICS_IMPACT_CAP = 65;
    private static final int MISSING_CONTACT_ATS_CAP = 55;
    private static final int EXCESS_PAGES_ATS_PENALTY = 10;

    private CvScoringArbiter() {
    }

    public static ObjectNode arbitrate(ObjectNode report, AuditResult audit) {
        JsonNode rawDims = report.path("dimensions");
        if (!rawDims.isObject()) {
            return report;
        }
        ObjectNode dims = (ObjectNode) rawDims;

        int impact = clamp(dims.path("experienceImpact").asInt(0));
        int hardSkills = clamp(dims.path("hardSkills").asInt(0));
        int ats = clamp(dims.path("atsCompliance").asInt(0));
        int clarity = clamp(dims.path("domainRelevance").asInt(0));
        int softSkills = clamp(dims.path("softSkills").asInt(0));

        List<String> capsApplied = new ArrayList<>();
        if (audit.statusOf("quantified_metrics") == Status.FAIL && impact > NO_METRICS_IMPACT_CAP) {
            impact = NO_METRICS_IMPACT_CAP;
            capsApplied.add("impact_capped_no_metrics");
        }
        if (audit.statusOf("contact_info") == Status.FAIL && ats > MISSING_CONTACT_ATS_CAP) {
            ats = MISSING_CONTACT_ATS_CAP;
            capsApplied.add("ats_capped_missing_contact");
        }
        if (audit.statusOf("page_count") == Status.FAIL) {
            ats = Math.max(0, ats - EXCESS_PAGES_ATS_PENALTY);
            capsApplied.add("ats_penalty_excess_pages");
        }

        dims.put("experienceImpact", impact);
        dims.put("hardSkills", hardSkills);
        dims.put("atsCompliance", ats);
        dims.put("domainRelevance", clarity);
        dims.put("softSkills", softSkills);

        int overall = (int) Math.round(
                (WEIGHT_IMPACT * impact
                        + WEIGHT_HARD_SKILLS * hardSkills
                        + WEIGHT_ATS * ats
                        + WEIGHT_CLARITY * clarity
                        + WEIGHT_SOFT_SKILLS * softSkills) / 100.0);
        report.put("overallScore", clamp(overall));

        ObjectNode rubric = report.putObject("rubric");
        rubric.put("id", RUBRIC_ID);
        rubric.put("name", RUBRIC_NAME);
        rubric.put("tier", tierFor(overall));
        ObjectNode weights = rubric.putObject("weights");
        weights.put("experienceImpact", WEIGHT_IMPACT);
        weights.put("hardSkills", WEIGHT_HARD_SKILLS);
        weights.put("atsCompliance", WEIGHT_ATS);
        weights.put("domainRelevance", WEIGHT_CLARITY);
        weights.put("softSkills", WEIGHT_SOFT_SKILLS);
        ArrayNode capsNode = rubric.putArray("capsApplied");
        capsApplied.forEach(capsNode::add);

        ArrayNode checksNode = report.putArray("atsChecks");
        for (AtsCheck check : audit.checks()) {
            ObjectNode c = checksNode.addObject();
            c.put("id", check.id());
            c.put("status", check.status().name());
            c.put("detail", check.detail());
            c.put("standard", check.standard());
        }
        return report;
    }

    static String tierFor(int score) {
        if (score >= 90) {
            return "TIER_1";
        }
        if (score >= 75) {
            return "TIER_2";
        }
        if (score >= 60) {
            return "TIER_3";
        }
        return "TIER_4";
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
