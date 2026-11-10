plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    implementation(project(":shared:web"))

    // Gradle exposes a project dependency's `implementation` deps at runtime only,
    // so the web/security/mail types this module compiles against are declared here.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // Every mail goes out over SMTP (Mailpit in the local stack).
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Public email-link rate limit: in-memory token buckets, held in a size- and idle-bounded cache.
    implementation("com.bucket4j:bucket4j_jdk17-core:${providers.gradleProperty("bucket4jVersion").get()}")
    implementation("com.github.ben-manes.caffeine:caffeine")
    // Sends are counted by key and outcome.
    implementation("io.micrometer:micrometer-core")
    // @Operation / @ApiResponse shape the OpenAPI snapshot; :boot:app brings springdoc at runtime,
    // so compile-only here.
    compileOnly("org.springdoc:springdoc-openapi-starter-common:${providers.gradleProperty("springdocVersion").get()}")
}
