plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    implementation(project(":shared:web"))
    implementation(project(":shared:security"))
    implementation(project(":modules:mail"))

    // Gradle exposes a project dependency's `implementation` deps at runtime only,
    // so the web/security types this module compiles against are declared here.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // @SecurityRequirements marks the pre-auth endpoints public in the OpenAPI snapshot; :boot:app
    // brings springdoc (and these annotations) at runtime, so compile-only here.
    compileOnly("org.springdoc:springdoc-openapi-starter-common:${providers.gradleProperty("springdocVersion").get()}")
    implementation(project(":shared:ratelimit"))
    implementation("tools.jackson.core:jackson-databind")
    // Confirmation mails the mail transport reports as failed are counted.
    implementation("io.micrometer:micrometer-core")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(testFixtures(project(":shared:ratelimit")))
}
