plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    implementation(project(":modules:user"))
    implementation(project(":modules:llm"))
    implementation(project(":modules:media"))
    implementation(project(":modules:mail"))
    implementation(project(":shared:web"))
    implementation(project(":shared:security"))

    // Gradle exposes a project dependency's `implementation` deps at runtime only,
    // so the web/security types this module compiles against are declared here.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("com.bucket4j:bucket4j_jdk17-core:${providers.gradleProperty("bucket4jVersion").get()}")
    implementation("org.commonmark:commonmark:${providers.gradleProperty("commonmarkVersion").get()}")
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
