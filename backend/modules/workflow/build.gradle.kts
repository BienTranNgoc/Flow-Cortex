plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    implementation(project(":modules:user"))
    implementation(project(":shared:web"))
    implementation(project(":shared:security"))

    // Web MVC + Spring Security/JWT types this module's OWN code needs on ITS
    // compile classpath (@RestController, @AuthenticationPrincipal, Jwt, ...).
    // Same compile-classpath gap as :modules:user (apply-report-slice2.md #3,
    // apply-report-slice4.md #1): :shared:web/:shared:security declare these
    // as `implementation`, which Gradle exposes to a consumer's RUNTIME
    // classpath only, not compile.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // InputResolver (task 5.4/R-042) parses a referenced step's JSON output
    // directly — declared explicitly rather than relying on it arriving
    // transitively via spring-boot-starter-web's own dependency graph.
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Async after-commit execution (design.md D5/R-050): the
    // @ApplicationModuleListener the orchestrator uses composes
    // @TransactionalEventListener(AFTER_COMMIT) with the persistent Event
    // Publication Registry — starter-jpa brings the JPA-backed registry
    // (events-api/events-core/events-jackson/events-jpa) so a
    // listener-or-app crash mid-run redelivers rather than losing the run
    // (its schema is V2026.09.22.003__event_publication.sql).
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")

    // Task 5.3's R-045 concurrency IT (RunStepClaimConcurrencyIT) needs a
    // real Postgres to prove the atomic UPDATE ... WHERE status = 'pending'
    // claim — the same "no H2" reasoning as design.md D7, scoped to this
    // module's own @DataJpaTest since :boot:app's Flyway-migrated schema
    // (and its FlowCortexApplication @SpringBootConfiguration) live in a
    // different Gradle subproject this module does not depend on.
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")

    // Boot 4.1 split @DataJpaTest into org.springframework.boot.data.jpa.test
    // .autoconfigure (spring-boot-data-jpa-test) and @AutoConfigureTestDatabase
    // into org.springframework.boot.jdbc.test.autoconfigure (spring-boot-jdbc-test,
    // pulled in transitively via spring-boot-starter-jdbc-test) — neither is
    // in spring-boot-test-autoconfigure any more (verified against the
    // actual 4.1.0 jars).
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
}
