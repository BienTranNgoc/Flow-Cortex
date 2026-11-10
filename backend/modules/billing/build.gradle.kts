plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    implementation(project(":modules:settings"))
    implementation(project(":modules:user"))
    implementation(project(":shared:web"))
    implementation(project(":shared:security"))

    // Gradle exposes a project dependency's `implementation` deps at runtime only,
    // so the web types this module compiles against are declared here.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("tools.jackson.core:jackson-databind")
    // The region gate counts each verdict.
    implementation("io.micrometer:micrometer-core")
    // @Operation / @Schema shape the OpenAPI snapshot; :boot:app brings springdoc at runtime, so
    // compile-only here.
    compileOnly("org.springdoc:springdoc-openapi-starter-common:${providers.gradleProperty("springdocVersion").get()}")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
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
