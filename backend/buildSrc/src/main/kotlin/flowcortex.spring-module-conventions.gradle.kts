// Convention plugin for a Spring Modulith library module (:modules:*,
// :shared:*): toolchain + -parameters + dependency-management BOMs + baseline
// module deps + Checkstyle/JaCoCo gates + persistence (JPA/Postgres/Flyway) +
// Jakarta Bean Validation (design.md D7, tasks 2.1/2.2/2.6).
plugins {
    `java-library`
    checkstyle
    jacoco
    id("io.spring.dependency-management")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Spring resolves @PathVariable/@RequestParam names via reflection; without
// -parameters, unnamed bindings fail at runtime.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

// Pin BOM-managed versions past fixable HIGH CVEs (Trivy scan in
// security-scan.yml): netty 4.2.16, pgjdbc 42.7.12.
extra["netty.version"] = "4.2.16.Final"
extra["postgresql.version"] = "42.7.12"

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
        mavenBom("org.springframework.modulith:spring-modulith-bom:2.1.0")
    }
}

dependencies {
    "implementation"("org.springframework.boot:spring-boot-starter")
    "implementation"("org.springframework.modulith:spring-modulith-starter-core")

    // Jakarta Bean Validation (Hibernate Validator) — convention: every inbound
    // request record across every module is @Valid with Jakarta constraints
    // (design.md D3/R-006, task 2.6); MethodArgumentNotValidException /
    // ConstraintViolationException are handled centrally by :shared:web's
    // GlobalExceptionHandler -> 400 ProblemDetail.
    "implementation"("org.springframework.boot:spring-boot-starter-validation")

    // Persistence — JPA + PostgreSQL + Flyway (design.md D7, task 2.2). No H2:
    // dev uses compose Postgres, tests use Testcontainers Postgres, so
    // ddl-auto: validate holds in every profile the app actually runs in.
    "implementation"("org.springframework.boot:spring-boot-starter-data-jpa")
    "runtimeOnly"("org.postgresql:postgresql")
    "implementation"("org.flywaydb:flyway-core")
    // Spring Boot 4 moved FlywayAutoConfiguration into its own module; without
    // it flyway-core is on the classpath but migrations never run (silent
    // no-op), and ddl-auto: validate then fails against an unmigrated schema.
    "implementation"("org.springframework.boot:spring-boot-flyway")
    // Flyway 10+ (Boot 4.x) splits per-database support into modules; without
    // the PostgreSQL one Flyway silently no-ops the same way.
    "runtimeOnly"("org.flywaydb:flyway-database-postgresql")

    "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    "testImplementation"("org.springframework.modulith:spring-modulith-starter-test")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

checkstyle {
    toolVersion = "10.21.0"
    configDirectory.set(rootProject.layout.projectDirectory.dir("config/checkstyle"))
    configFile = rootProject.layout.projectDirectory.file("config/checkstyle/checkstyle.xml").asFile
    // Make the suppressions file path available to checkstyle.xml.
    configProperties["checkstyle.suppressions.file"] =
        rootProject.layout.projectDirectory.file("config/checkstyle/suppressions.xml").asFile.absolutePath
    maxWarnings = 0
    // Enforced from the start: a Checkstyle violation fails the build. No
    // ignoreFailures grace period — a gate nobody can fail is not a gate.
    isIgnoreFailures = false
}

// Ensure checkstyle runs as part of `check` (the checkstyle plugin already wires
// checkstyleMain / checkstyleTest into the check task by default).
tasks.named("check") {
    dependsOn(tasks.withType<Checkstyle>())
}

// ---------------------------------------------------------------------------
// JaCoCo code coverage.
//
// Coverage exclusions: generated / boilerplate that is not meaningfully
// unit-tested (Application classes, Spring config, package-info, ...).
// ---------------------------------------------------------------------------
val jacocoExclusions = listOf(
    "**/*Application*",
    "**/config/**",
    "**/*Config*",
    "**/package-info*",
)

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(
        files(classDirectories.files.map { dir ->
            fileTree(dir) { exclude(jacocoExclusions) }
        }),
    )
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestCoverageVerification {
    classDirectories.setFrom(
        files(classDirectories.files.map { dir ->
            fileTree(dir) { exclude(jacocoExclusions) }
        }),
    )
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                // Starts higher than DigiShield's own ratchet (0.30->0.50 over
                // time): FC is greenfield and small, so 0.50 is reachable from
                // the first slice rather than a later target (design.md D7).
                minimum = "0.50".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
