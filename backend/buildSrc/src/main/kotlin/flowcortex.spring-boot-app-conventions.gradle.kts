// Convention plugin for the executable Spring Boot app (:boot:app): Boot plugin + bootJar +
// Modulith test starter + the same Checkstyle/JaCoCo gates as spring-module-conventions. Kept
// separate (not composing that one) since the app applies plain `java`, not `java-library`.
plugins {
    java
    checkstyle
    jacoco
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Spring resolves @PathVariable/@RequestParam names via reflection; the Boot plugin already
// adds -parameters, but keep it explicit so it survives refactors.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

// Pin BOM-managed versions past fixable HIGH CVEs (Trivy scan): netty 4.2.16, pgjdbc 42.7.12.
extra["netty.version"] = "4.2.16.Final"
extra["postgresql.version"] = "42.7.12"

// Boot plugin + dependency-management auto-imports spring-boot-dependencies for the applied
// Boot version, so only the Modulith BOM needs importing explicitly here.
val springModulithVersion = providers.gradleProperty("springModulithVersion").get()

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:$springModulithVersion")
    }
}

dependencies {
    "testImplementation"("org.springframework.modulith:spring-modulith-starter-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

checkstyle {
    toolVersion = "10.21.0"
    configDirectory.set(rootProject.layout.projectDirectory.dir("config/checkstyle"))
    configFile = rootProject.layout.projectDirectory.file("config/checkstyle/checkstyle.xml").asFile
    configProperties["checkstyle.suppressions.file"] =
        rootProject.layout.projectDirectory.file("config/checkstyle/suppressions.xml").asFile.absolutePath
    maxWarnings = 0
    isIgnoreFailures = false
}

tasks.named("check") {
    dependsOn(tasks.withType<Checkstyle>())
}

// Same JaCoCo exclusions as the module convention — generated/boilerplate, not unit-tested.
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
                minimum = "0.50".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
