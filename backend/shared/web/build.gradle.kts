plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    // ProblemDetail / @RestControllerAdvice / ResponseEntityExceptionHandler for the global error advice.
    implementation("org.springframework.boot:spring-boot-starter-web")
    // AuthenticationException / AccessDeniedException, which :shared:security's chain
    // hands to this advice for 401/403 — just spring-security-core, not the full starter.
    implementation("org.springframework.security:spring-security-core")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
