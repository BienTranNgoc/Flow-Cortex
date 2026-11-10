plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    implementation(project(":shared:web"))
    implementation(project(":shared:ratelimit"))

    // Gradle exposes a project dependency's `implementation` deps at runtime only,
    // so the web types this module compiles against are declared here.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    implementation("tools.jackson.core:jackson-databind")
    // @RequestBody / @ApiResponse document the raw-body journey route; :boot:app brings springdoc at
    // runtime, so compile-only here.
    compileOnly("org.springdoc:springdoc-openapi-starter-common:${providers.gradleProperty("springdocVersion").get()}")
    // The region gate counts each verdict.
    implementation("io.micrometer:micrometer-core")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(testFixtures(project(":shared:ratelimit")))
    // The JSON Schema validator proves the records match contracts/*.schema.json;
    // it stays off the runtime classpath, where Bean Validation enforces the same rules.
    testImplementation("com.networknt:json-schema-validator:${providers.gradleProperty("jsonSchemaValidatorVersion").get()}")
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

// Rebuilds the committed VN address snapshot from the five registries, or from a directory of
// already-downloaded files with -PfromDir=<dir>; -Pout=<file> overrides the output path.
tasks.register<JavaExec>("generateVnIpSnapshot") {
    group = "billing"
    description = "Regenerates billing/region/vn-ip-ranges.snapshot.json from the regional registries"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.flowcortex.billing.region.VnIpSnapshotGenerator")
    val out = providers.gradleProperty("out").orElse(
        layout.projectDirectory.file("src/main/resources/billing/region/vn-ip-ranges.snapshot.json").asFile.absolutePath,
    )
    val fromDir = providers.gradleProperty("fromDir")
    argumentProviders.add(
        CommandLineArgumentProvider {
            buildList {
                add("--out")
                add(out.get())
                if (fromDir.isPresent) {
                    add("--from-dir")
                    add(fromDir.get())
                }
            }
        },
    )
}
