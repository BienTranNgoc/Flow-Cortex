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
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("tools.jackson.core:jackson-databind")
    implementation("io.micrometer:micrometer-core")
    implementation("io.micrometer:context-propagation")
    // @SecurityRequirements / @Operation shape the OpenAPI snapshot; :boot:app brings springdoc at
    // runtime, so compile-only here.
    compileOnly("org.springdoc:springdoc-openapi-starter-common:${providers.gradleProperty("springdocVersion").get()}")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("io.micrometer:micrometer-observation-test")
}

// The service and controllers of this module run against real Postgres, RabbitMQ and MinIO in
// ContentDocumentsIT and ContentMediaIT, which live in :boot:app (the only place the whole
// application context assembles). Their execution data counts toward this module's line floor.
tasks.jacocoTestCoverageVerification {
    dependsOn(":boot:app:test")
    executionData(project(":boot:app").layout.buildDirectory.file("jacoco/test.exec"))
}
