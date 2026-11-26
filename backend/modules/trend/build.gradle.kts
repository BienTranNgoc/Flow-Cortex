plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    implementation(project(":modules:jobs"))
    implementation(project(":shared:web"))
    implementation(project(":shared:security"))

    // Gradle exposes a project dependency's `implementation` deps at runtime only,
    // so the web/security types this module compiles against are declared here.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // @ApiResponse / @Operation shape the OpenAPI snapshot; :boot:app brings springdoc at runtime,
    // so compile-only here.
    compileOnly("org.springdoc:springdoc-openapi-starter-common:${providers.gradleProperty("springdocVersion").get()}")
}
