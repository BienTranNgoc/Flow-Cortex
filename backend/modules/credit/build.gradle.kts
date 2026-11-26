plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    implementation(project(":modules:user"))
    implementation(project(":shared:web"))
    implementation(project(":shared:security"))

    // Gradle exposes a project dependency's `implementation` deps at runtime only,
    // so the web/security types this module compiles against are declared here.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // The signup grant listens to the user-created event through the persistent publication registry.
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")
}

tasks.withType<Test> {
    // contracts/step-failure-taxonomy.json (the repo root is backend/'s parent) holds the codes whose charge is kept.
    // Declared as an input too, or a contract-only edit would be served from the build cache.
    val contracts = rootProject.projectDir.parentFile.resolve("contracts")
    systemProperty("flowcortex.contracts.dir", contracts.absolutePath)
    inputs.dir(contracts).withPropertyName("contracts").withPathSensitivity(PathSensitivity.RELATIVE)
}
