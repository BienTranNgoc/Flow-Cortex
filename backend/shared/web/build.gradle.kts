plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    // ProblemDetail / @RestControllerAdvice / ResponseEntityExceptionHandler /
    // dispatcher servlet classes for the global error advice (design.md D3).
    implementation("org.springframework.boot:spring-boot-starter-web")

    // AuthenticationException / AccessDeniedException are the exception types
    // :shared:security's filter chain hands to this advice (401/403 mapping,
    // D4) — a lightweight compile dependency, not the full security starter;
    // the actual filter chain lives in :shared:security.
    implementation("org.springframework.security:spring-security-core")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
