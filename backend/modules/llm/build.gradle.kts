plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    // RestClient and its HTTP request factories.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("tools.jackson.core:jackson-databind")
    // Parses answers to strip links, images and HTML.
    implementation("org.commonmark:commonmark:${providers.gradleProperty("commonmarkVersion").get()}")
    // Every provider call is timed by outcome and model.
    implementation("io.micrometer:micrometer-core")
}
