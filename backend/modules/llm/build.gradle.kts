plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    implementation(project(":shared:web"))
    implementation(project(":shared:security"))

    // RestClient and its HTTP request factories.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("tools.jackson.core:jackson-databind")
    // Every provider call is timed by outcome and model.
    implementation("io.micrometer:micrometer-core")
    // @Operation / @ApiResponse shape the OpenAPI snapshot; :boot:app brings springdoc at runtime,
    // so compile-only here.
    compileOnly("org.springdoc:springdoc-openapi-starter-common:${providers.gradleProperty("springdocVersion").get()}")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
