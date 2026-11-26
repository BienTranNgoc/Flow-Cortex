plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    implementation(project(":modules:user"))
    implementation(project(":modules:jobs"))
    implementation(project(":modules:notification"))
    implementation(project(":modules:mail"))
    implementation(project(":shared:web"))

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Gradle exposes a project dependency's `implementation` deps at runtime only,
    // so the web/security types this module compiles against are declared here.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // @Operation / @Schema shape the OpenAPI snapshot; :boot:app brings springdoc at runtime, so compile-only here.
    compileOnly("org.springdoc:springdoc-openapi-starter-common:${providers.gradleProperty("springdocVersion").get()}")
}

// The actions, listing and controllers of this module run against real Postgres in the Trash ITs, which live in
// :boot:app (the only place the whole application context assembles). Their execution data counts toward this
// module's line floor.
tasks.jacocoTestCoverageVerification {
    dependsOn(":boot:app:test")
    executionData(project(":boot:app").layout.buildDirectory.file("jacoco/test.exec"))
}
