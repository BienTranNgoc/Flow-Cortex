plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    implementation(project(":modules:workflow"))
    implementation(project(":modules:media"))
    implementation(project(":modules:space"))
    implementation(project(":modules:jobs"))
    implementation(project(":shared:web"))

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // The registration listeners run through the persistent publication registry.
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")
}
