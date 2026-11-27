package com.flowcortex.cvcheck.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CvValidityGateTest {

    @Test
    void acceptsValidVietnameseAndEnglishCvs() {
        String validCv = """
                NGUYEN VAN AN - Backend Developer
                Email: an@example.com | Phone: 0912345678 | github.com/an-dev
                EXPERIENCE
                Software Engineer at ABC Tech (2022 - Present). Developed REST APIs with Java Spring Boot and PostgreSQL.
                Built caching layer with Redis and deployed microservices with Docker and GitHub Actions.
                EDUCATION
                Bachelor of Computer Science, University of Technology (2018 - 2022).
                SKILLS
                Java, Spring Boot, SQL, Docker, Git.
                """;
        assertThat(CvValidityGate.looksLikeCv(validCv, 1)).isTrue();
    }

    @Test
    void rejectsTooShortOrNonCvDocumentsOrExcessPages() {
        assertThat(CvValidityGate.looksLikeCv("Invoice #12345 Total: 500,000 VND", 1)).isFalse();
        assertThat(CvValidityGate.looksLikeCv("Chapter 1 Introduction to Physics. ".repeat(30), 1)).isFalse();
        assertThat(CvValidityGate.looksLikeCv("Experience Education Skills ".repeat(30), 12)).isFalse();
    }
}
