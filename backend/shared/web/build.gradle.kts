plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    // ProblemDetail / @RestControllerAdvice / ResponseEntityExceptionHandler for the global error advice.
    implementation("org.springframework.boot:spring-boot-starter-web")
    // AuthenticationException / AccessDeniedException, which :shared:security's chain
    // hands to this advice for 401/403 — just spring-security-core, not the full starter.
    implementation("org.springframework.security:spring-security-core")
    // @Schema marks PageView's fields required in the OpenAPI snapshot; :boot:app brings springdoc
    // at runtime, so compile-only here.
    compileOnly("org.springdoc:springdoc-openapi-starter-common:${providers.gradleProperty("springdocVersion").get()}")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}

tasks.withType<Test> {
    // MessageBundleParityTest reads every module's main sources for the message codes they name.
    // Declared as an input too, or a source-only edit would be served from the build cache.
    systemProperty("flowcortex.backend.dir", rootProject.projectDir.absolutePath)
    inputs.files(
        fileTree(rootProject.projectDir) {
            include("modules/*/src/main/java/**/*.java", "shared/*/src/main/java/**/*.java")
        },
    ).withPropertyName("backendMainSources").withPathSensitivity(PathSensitivity.RELATIVE)
}
