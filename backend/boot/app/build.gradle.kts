plugins {
    id("flowcortex.spring-boot-app-conventions")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    // Telemetry: spans and logs over OTLP (export off unless OTEL_*_EXPORTER=otlp), metrics scraped
    // from the management port, module and JDBC observations. JDBC parameter values stay out.
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:${providers.gradleProperty("otelLogbackAppenderVersion").get()}")
    implementation("org.springframework.modulith:spring-modulith-starter-insight")
    implementation("net.ttddyy.observation:datasource-micrometer-spring-boot:${providers.gradleProperty("datasourceMicrometerVersion").get()}")
    // The management chain matches actuator endpoints with Boot's EndpointRequest.
    implementation("org.springframework.boot:spring-boot-starter-security")
    // Pinned exactly: 3.1.1 is where springdoc's Boot 4 / Jackson 3 introspection settled.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${providers.gradleProperty("springdocVersion").get()}")
    // Every module + shared library, so Modulith scans the whole com.flowcortex tree.
    implementation(project(":modules:user"))
    implementation(project(":modules:workflow"))
    implementation(project(":modules:media"))
    implementation(project(":shared:web"))
    implementation(project(":shared:security"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boot 4 moved the MVC test slice out of spring-boot-starter-test.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:${providers.gradleProperty("testcontainersVersion").get()}"))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:rabbitmq")
    testImplementation("org.testcontainers:minio")
    testImplementation("com.networknt:json-schema-validator:${providers.gradleProperty("jsonSchemaValidatorVersion").get()}")
    // The media ITs play the worker: raw AMQP on the broker, raw S3 on the object store.
    testImplementation("org.springframework.boot:spring-boot-starter-amqp")
    testImplementation(platform("software.amazon.awssdk:bom:${providers.gradleProperty("awsSdkVersion").get()}"))
    testImplementation("software.amazon.awssdk:s3")
    // The module convention puts JPA/Flyway/security on the runtime classpath only;
    // this module's own tests compile against them directly.
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.springframework.boot:spring-boot-flyway")
    // spring-boot-resttestclient's auto-config needs RestTemplateBuilder (spring-boot-restclient).
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.springframework.boot:spring-boot-restclient")
    testImplementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}

tasks.withType<Test> {
    // A real env var, as a deployed environment would set it; clearly a test fixture.
    environment("JWT_SECRET", "test-only-jwt-signing-secret-not-a-real-credential-32b")
    // Absolute path to the committed OpenAPI snapshot; the repo root is backend/'s parent.
    systemProperty(
        "flowcortex.openapi.docsFile",
        rootProject.projectDir.parentFile.resolve("docs/openapi.yaml").absolutePath,
    )
    // The round-trip IT validates the raw job bytes against the committed schema; declared as an
    // input too, or a schema-only edit would be served from the build cache.
    systemProperty(
        "flowcortex.contracts.dir",
        rootProject.projectDir.parentFile.resolve("contracts").absolutePath,
    )
    inputs.dir(rootProject.projectDir.parentFile.resolve("contracts"))
        .withPropertyName("contracts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
