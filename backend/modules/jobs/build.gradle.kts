plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    // The resubmitter reads and deletes publications through the event publication registry.
    implementation("org.springframework.modulith:spring-modulith-events-api")
    // EventPublicationRepository, the only way to delete a publication the registry gave up on.
    implementation("org.springframework.modulith:spring-modulith-events-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")
}
