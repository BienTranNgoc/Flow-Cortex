plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    implementation(project(":modules:user"))
    implementation(project(":shared:web"))
    implementation(project(":shared:security"))

    // Gradle exposes a project dependency's `implementation` deps at runtime only,
    // so the web/security/broker types this module compiles against are declared here.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    // The wire message between instances is JSON, read and written with Jackson 3 directly.
    implementation("tools.jackson.core:jackson-databind")
    // @Operation / @ApiResponse shape the OpenAPI snapshot; :boot:app brings springdoc at runtime,
    // so compile-only here.
    compileOnly("org.springdoc:springdoc-openapi-starter-common:${providers.gradleProperty("springdocVersion").get()}")
}
