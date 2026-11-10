plugins {
    id("flowcortex.spring-module-conventions")
    `java-test-fixtures`
}

dependencies {
    val bucket4jVersion = providers.gradleProperty("bucket4jVersion").get()
    implementation("com.bucket4j:bucket4j_jdk17-core:$bucket4jVersion")
    implementation("com.bucket4j:bucket4j_jdk17-postgresql:$bucket4jVersion")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("io.micrometer:micrometer-core")

    testFixturesImplementation("com.bucket4j:bucket4j_jdk17-core:$bucket4jVersion")
}
