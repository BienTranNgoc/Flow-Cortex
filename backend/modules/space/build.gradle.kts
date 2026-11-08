plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    implementation(project(":modules:user"))
    implementation(project(":shared:web"))

    // Gradle exposes a project dependency's `implementation` deps at runtime only,
    // so the web types this module compiles against are declared here.
    implementation("org.springframework.boot:spring-boot-starter-web")
    // The activation listener runs through the persistent publication registry.
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")
}
