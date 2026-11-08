plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    // RestClient and its HTTP request factories.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("tools.jackson.core:jackson-databind")
    // Every provider call is timed by outcome and model.
    implementation("io.micrometer:micrometer-core")
}
