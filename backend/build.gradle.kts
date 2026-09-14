plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.flowcortex"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        // Matches the Temurin 25 install this machine uses. Gradle has no foojay
        // resolver here, so JAVA_HOME must point at a JDK 25.
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Spring resolves @PathVariable/@RequestParam names via reflection; without
// -parameters, unnamed bindings fail at runtime. The Boot plugin adds this to
// the app project, but keep it explicit so it survives a split into modules.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4 split the MVC test slice (@WebMvcTest / @AutoConfigureMockMvc)
    // out of spring-boot-starter-test into its own starter.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------
// JaCoCo — MEASURE first, do not gate yet (Tầng 1). Run:
//   ./gradlew test        -> report at build/reports/jacoco/test/html/index.html
// Turn it into a gate once you know your real number: uncomment the
// verification block below and wire it into `check`. See docs/ADR.md (ADR-0001).
// ---------------------------------------------------------------------------
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

// tasks.jacocoTestCoverageVerification {
//     violationRules {
//         rule {
//             limit {
//                 counter = "LINE"
//                 value = "COVEREDRATIO"
//                 minimum = "0.50".toBigDecimal() // a ratchet, raise it over time
//             }
//         }
//     }
// }
// tasks.named("check") { dependsOn(tasks.jacocoTestCoverageVerification) }
