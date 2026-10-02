plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    // Modulith externalizes @Externalized events to the broker through the event
    // publication registry (the outbox the workflow module already persists).
    implementation("org.springframework.modulith:spring-modulith-events-amqp")
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")
    implementation("org.springframework.modulith:spring-modulith-events-api")
    // The api records are the wire shape of the contract. The inbound listeners read them with
    // their own strict mapper, so an unknown field is rejected rather than ignored.
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    implementation("tools.jackson.core:jackson-databind")
    implementation(platform("software.amazon.awssdk:bom:${providers.gradleProperty("awsSdkVersion").get()}"))
    implementation("software.amazon.awssdk:s3")
    // The object store's own health indicator (HeadBucket on the media bucket).
    implementation("org.springframework.boot:spring-boot-health")

    // The JSON Schema validator proves the records match contracts/*.schema.json;
    // it stays off the runtime classpath, where Bean Validation enforces the same rules.
    testImplementation("com.networknt:json-schema-validator:${providers.gradleProperty("jsonSchemaValidatorVersion").get()}")
    testImplementation(platform("org.testcontainers:testcontainers-bom:${providers.gradleProperty("testcontainersVersion").get()}"))
    testImplementation("org.testcontainers:rabbitmq")
    testImplementation("org.testcontainers:minio")
}

tasks.withType<Test> {
    // Absolute path to the committed contract schemas; the repo root is backend/'s parent.
    // Declared as an input too, or a schema-only edit would be served from the build cache.
    systemProperty(
        "flowcortex.contracts.dir",
        rootProject.projectDir.parentFile.resolve("contracts").absolutePath,
    )
    inputs.dir(rootProject.projectDir.parentFile.resolve("contracts"))
        .withPropertyName("contracts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
