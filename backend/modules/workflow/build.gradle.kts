plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    implementation(project(":modules:user"))
    implementation(project(":modules:media"))
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
    // The reaper and the result path count what they close and what they discard.
    implementation("io.micrometer:micrometer-core")
    // The run id travels as trace baggage on everything a run sends.
    implementation("io.micrometer:micrometer-tracing")
    // @Schema marks always-present response fields required in the OpenAPI snapshot; :boot:app
    // brings springdoc (and these annotations) at runtime, so compile-only here.
    compileOnly("org.springdoc:springdoc-openapi-starter-common:${providers.gradleProperty("springdocVersion").get()}")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    // The atomic step-claim IT needs a real Postgres (no H2), scoped to this module's
    // own @DataJpaTest since the Flyway-migrated schema lives in :boot:app.
    testImplementation(platform("org.testcontainers:testcontainers-bom:${providers.gradleProperty("testcontainersVersion").get()}"))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    // Boot 4.1 moved @DataJpaTest / @AutoConfigureTestDatabase out of
    // spring-boot-test-autoconfigure into their own starters.
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
}

tasks.withType<Test> {
    // The Flyway migrations live in :boot:app; the step-transition IT runs on that real schema.
    // Declared as an input too, or a migration-only edit would be served from the build cache.
    val migrations = rootProject.projectDir.resolve("boot/app/src/main/resources/db/migration")
    systemProperty("flowcortex.migrations.dir", migrations.absolutePath)
    inputs.dir(migrations).withPropertyName("migrations").withPathSensitivity(PathSensitivity.RELATIVE)
}
