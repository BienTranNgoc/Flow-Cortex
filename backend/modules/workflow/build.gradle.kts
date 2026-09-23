plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    implementation(project(":modules:user"))
    implementation(project(":shared:web"))
    implementation(project(":shared:security"))

    // Gradle exposes a project dependency's `implementation` deps at runtime only,
    // so the web/security types this module compiles against are declared here.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // InputResolver parses a referenced step's JSON output directly.
    implementation("com.fasterxml.jackson.core:jackson-databind")
    // Persistent Event Publication Registry: an after-commit listener crash mid-run
    // redelivers instead of losing the run (schema: V2026.09.22.003__event_publication.sql).
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    // The atomic step-claim IT needs a real Postgres (no H2), scoped to this module's
    // own @DataJpaTest since the Flyway-migrated schema lives in :boot:app.
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    // Boot 4.1 moved @DataJpaTest / @AutoConfigureTestDatabase out of
    // spring-boot-test-autoconfigure into their own starters.
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
}
