plugins {
    id("flowcortex.spring-boot-app-conventions")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.modulith:spring-modulith-starter-core")

    // Contract-first OpenAPI pipeline (design.md D6, task 3.1). Pinned to
    // 3.1.1 EXACTLY — the version that settled springdoc's early-Boot-4/
    // Jackson-3 introspection friction (issues #3169/#3268); do not float
    // this to a range.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")

    // Business modules + shared libraries (all of them, so Modulith can scan
    // the full module tree rooted at com.flowcortex).
    implementation(project(":modules:user"))
    implementation(project(":modules:workflow"))
    implementation(project(":shared:web"))
    implementation(project(":shared:security"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4 split the MVC test slice (@WebMvcTest / @AutoConfigureMockMvc)
    // out of spring-boot-starter-test into its own starter.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Testcontainers Postgres (design.md D7 — no H2; every DB-backed test,
    // including the plain context-loads smoke test, now needs a real
    // Postgres since JPA/Flyway/the driver are unconditionally on the
    // classpath via the module convention, tasks 2.2/2.3).
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")

    // JPA/Flyway/Postgres for :boot:app's OWN test code (task 2.3's
    // probetest.ProbeEntity + the persistence-validation IT). The module
    // convention already brings these to :modules:*/:shared:* at runtime
    // (transitively, on :boot:app's runtime classpath via the project
    // dependencies below) but NOT to :boot:app's own compile classpath —
    // :boot:app applies flowcortex.spring-boot-app-conventions, a separate
    // plugin that does not declare them.
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.springframework.boot:spring-boot-flyway")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")

    // TestRestTemplate (SecurityFilterChainIT, RANDOM_PORT real-HTTP checks).
    // spring-boot-resttestclient's auto-configuration itself needs
    // RestTemplateBuilder (spring-boot-restclient) on the classpath to
    // evaluate its @ConditionalOnMissingBean checks — not pulled in
    // transitively by spring-boot-starter-test.
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.springframework.boot:spring-boot-restclient")

    // Jwt/JwtDecoder for :boot:app's OWN test code (task 4.8's
    // DevDataSeederIT decodes a minted token to read its subject claim).
    // Same compile-classpath gap as above: :shared:security declares this as
    // `implementation`, which exposes it to :boot:app's runtime classpath but
    // not its own test-compile classpath (apply-report-slice2.md #3).
    testImplementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
}

tasks.withType<Test> {
    // JWT_SECRET (design.md D4) as a real env var for every test in this
    // module, exactly like a deployed environment would set it — avoids
    // relying on the 'dev' profile default (which is for bare `bootRun`) or
    // shadowing application.yml with a second test-only copy. Clearly a
    // test fixture, not a real credential.
    environment("JWT_SECRET", "test-only-jwt-signing-secret-not-a-real-credential-32b")

    // Task 3.1 — absolute path to the committed OpenAPI snapshot, resolved
    // from the Gradle root project directory rather than the test task's
    // working directory (which Gradle does not guarantee is the repo root).
    // rootProject here is `backend/`, so its parent is the repo root.
    systemProperty(
        "flowcortex.openapi.docsFile",
        rootProject.projectDir.parentFile.resolve("docs/openapi.yaml").absolutePath,
    )
}
