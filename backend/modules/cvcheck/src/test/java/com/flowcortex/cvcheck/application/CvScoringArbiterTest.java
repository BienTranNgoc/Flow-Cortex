package com.flowcortex.cvcheck.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.cvcheck.application.CvDeterministicAuditor.AuditResult;
import com.flowcortex.cvcheck.application.CvDeterministicAuditor.Status;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class CvScoringArbiterTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void computesWeightedScoreAndPassesStrongCv() {
        String strongCv = """
                Nguyen Van An
                Email: an.nguyen@example.com | Phone: +84 912 345 678 | github.com/annguyen

                EXPERIENCE
                Backend Engineer
                - Built 15+ REST APIs serving 50,000+ users with Spring Boot and PostgreSQL.
                - Optimized Redis caching, reducing p95 latency by 73% from 450ms to 120ms.
                - Led CI/CD automation, cutting deployment time by 40% and improving test coverage to 85%.
                - Engineered payment gateway integration handling $2M monthly volume across 3 services.

                EDUCATION
                Bachelor of Computer Science - Hanoi University of Science and Technology

                SKILLS
                Java, Spring Boot, PostgreSQL, Redis, Docker, Kubernetes, AWS
                """ + " project details ".repeat(90);

        AuditResult audit = CvDeterministicAuditor.audit(strongCv, 1);
        assertThat(audit.statusOf("page_count")).isEqualTo(Status.PASS);
        assertThat(audit.statusOf("contact_info")).isEqualTo(Status.PASS);
        assertThat(audit.statusOf("standard_sections")).isEqualTo(Status.PASS);
        assertThat(audit.statusOf("quantified_metrics")).isEqualTo(Status.PASS);
        assertThat(audit.statusOf("word_count")).isEqualTo(Status.PASS);
        assertThat(audit.statusOf("action_verbs")).isEqualTo(Status.PASS);

        ObjectNode report = (ObjectNode) json.readTree("""
                {"overallScore":50,"dimensions":{
                  "experienceImpact":90,"hardSkills":88,"atsCompliance":92,"domainRelevance":84,"softSkills":80}}
                """);
        CvScoringArbiter.arbitrate(report, audit);

        // 0.30*90 + 0.25*88 + 0.20*92 + 0.15*84 + 0.10*80 = 27 + 22 + 18.4 + 12.6 + 8 = 88
        assertThat(report.path("overallScore").asInt()).isEqualTo(88);
        assertThat(report.path("rubric").path("id").asString()).isEqualTo(CvScoringArbiter.RUBRIC_ID);
        assertThat(report.path("rubric").path("tier").asString()).isEqualTo("TIER_2");
        assertThat(report.path("rubric").path("capsApplied")).isEmpty();
        assertThat(report.path("atsChecks")).hasSize(6);
    }

    @Test
    void appliesHardGateCapsWhenMetricsAndContactAreMissingAndExcessPages() {
        String weakCv = "Responsible for working on tasks. Duties included helping the team.";
        AuditResult audit = CvDeterministicAuditor.audit(weakCv, 4);

        assertThat(audit.statusOf("quantified_metrics")).isEqualTo(Status.FAIL);
        assertThat(audit.statusOf("contact_info")).isEqualTo(Status.FAIL);
        assertThat(audit.statusOf("page_count")).isEqualTo(Status.FAIL);

        ObjectNode report = (ObjectNode) json.readTree("""
                {"overallScore":95,"dimensions":{
                  "experienceImpact":90,"hardSkills":80,"atsCompliance":85,"domainRelevance":70,"softSkills":70}}
                """);
        CvScoringArbiter.arbitrate(report, audit);

        // experienceImpact capped to 65; atsCompliance capped to 55 then -10 for >3 pages = 45
        // 0.30*65 + 0.25*80 + 0.20*45 + 0.15*70 + 0.10*70 = 19.5 + 20 + 9 + 10.5 + 7 = 66
        assertThat(report.path("dimensions").path("experienceImpact").asInt()).isEqualTo(65);
        assertThat(report.path("dimensions").path("atsCompliance").asInt()).isEqualTo(45);
        assertThat(report.path("overallScore").asInt()).isEqualTo(66);
        assertThat(report.path("rubric").path("tier").asString()).isEqualTo("TIER_3");
        assertThat(report.path("rubric").path("capsApplied")).hasSize(3);
    }

    @Test
    void formatsAtsChecksInEnglishWhenRequested() {
        AuditResult audit = CvDeterministicAuditor.audit("No metrics here", 1, "en");
        assertThat(audit.checks()).allSatisfy(c -> assertThat(c.detail()).doesNotContain("trang", "từ", "Thiếu"));
        assertThat(audit.checks().getFirst().detail()).contains("1 page");
    }
}
