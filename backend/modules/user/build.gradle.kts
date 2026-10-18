plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    implementation(project(":shared:web"))
    implementation(project(":shared:security"))

    // Gradle exposes a project dependency's `implementation` deps at runtime only,
    // so the web/security types this module compiles against are declared here.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // @SecurityRequirements marks the pre-auth endpoints public in the OpenAPI snapshot; :boot:app
    // brings springdoc (and these annotations) at runtime, so compile-only here.
    compileOnly("org.springdoc:springdoc-openapi-starter-common:${providers.gradleProperty("springdocVersion").get()}")
    // Registration rate limits: in-memory token buckets, held in a size- and idle-bounded cache.
    implementation("com.bucket4j:bucket4j_jdk17-core:${providers.gradleProperty("bucket4jVersion").get()}")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("tools.jackson.core:jackson-databind")
    // Email confirmation links go out over SMTP (Mailpit in the local stack).
    implementation("org.springframework.boot:spring-boot-starter-mail")
    // Confirmation mails the SMTP server refused are counted.
    implementation("io.micrometer:micrometer-core")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
}
