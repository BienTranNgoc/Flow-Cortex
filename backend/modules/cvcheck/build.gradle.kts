plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    implementation(project(":modules:llm"))
    implementation(project(":modules:jobs"))
    implementation(project(":shared:web"))
    implementation(project(":shared:security"))

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("tools.jackson.core:jackson-databind")
    implementation("org.apache.pdfbox:pdfbox:3.0.4")
    compileOnly("org.springdoc:springdoc-openapi-starter-common:${providers.gradleProperty("springdocVersion").get()}")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
