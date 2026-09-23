// Convention plugin for a Spring Modulith library module: toolchain, -parameters, BOMs,
// Checkstyle/JaCoCo gates, persistence (JPA/Postgres/Flyway), Jakarta Bean Validation.
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

// Spring resolves @PathVariable/@RequestParam names via reflection; -parameters is required.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

// Pin BOM-managed versions past fixable HIGH CVEs (Trivy scan): netty 4.2.16, pgjdbc 42.7.12.
extra["netty.version"] = "4.2.16.Final"
extra["postgresql.version"] = "42.7.12"

val springBootVersion = providers.gradleProperty("springBootVersion").get()
val springModulithVersion = providers.gradleProperty("springModulithVersion").get()

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
        mavenBom("org.springframework.modulith:spring-modulith-bom:$springModulithVersion")
    }
}

dependencies {
    "implementation"("org.springframework.boot:spring-boot-starter")
    "implementation"("org.springframework.modulith:spring-modulith-starter-core")

    // Jakarta Bean Validation: every inbound request record is @Valid; violations are handled
    // centrally by :shared:web's GlobalExceptionHandler -> 400 ProblemDetail.
    "implementation"("org.springframework.boot:spring-boot-starter-validation")

    // Persistence: JPA + PostgreSQL + Flyway. No H2 — dev/test both use real Postgres, so
    // ddl-auto: validate holds in every profile the app runs in.
    "implementation"("org.springframework.boot:spring-boot-starter-data-jpa")
    "runtimeOnly"("org.postgresql:postgresql")
    "implementation"("org.flywaydb:flyway-core")
    // Spring Boot 4 moved FlywayAutoConfiguration out; without it migrations silently no-op.
    "implementation"("org.springframework.boot:spring-boot-flyway")
    // Flyway 10+ splits per-database support into modules; without this one it silently no-ops.
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
    // A Checkstyle violation fails the build — no ignoreFailures grace period.
    isIgnoreFailures = false
}

// checkstyle already wires into `check` by default; this makes it explicit.
tasks.named("check") {
    dependsOn(tasks.withType<Checkstyle>())
}

// JaCoCo exclusions: generated/boilerplate not meaningfully unit-tested.
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
                // Greenfield + small codebase: 0.50 is reachable from the first slice.
                minimum = "0.50".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
