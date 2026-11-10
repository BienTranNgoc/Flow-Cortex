plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    implementation(project(":modules:user"))
    implementation(project(":modules:space"))
    implementation(project(":modules:media"))
    implementation(project(":modules:workflow"))
    implementation(project(":shared:web"))

    // Gradle exposes a project dependency's `implementation` deps at runtime only,
    // so the web/security types this module compiles against are declared here.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // The Memory population listener runs through the persistent publication registry.
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")
    // @Operation / @Schema give the routes their operation ids and required fields in the OpenAPI snapshot;
    // :boot:app brings springdoc at runtime, so compile-only here.
    compileOnly("org.springdoc:springdoc-openapi-starter-common:${providers.gradleProperty("springdocVersion").get()}")
}
