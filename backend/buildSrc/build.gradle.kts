import java.util.Properties

plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

// buildSrc does not inherit the root gradle.properties; read it so the platform versions have one home.
val rootProps = Properties().apply {
    projectDir.parentFile.resolve("gradle.properties").inputStream().use { load(it) }
}
val springBootVersion: String = rootProps.getProperty("springBootVersion")
val springDependencyManagementVersion: String = rootProps.getProperty("springDependencyManagementVersion")

dependencies {
    implementation("org.springframework.boot:spring-boot-gradle-plugin:$springBootVersion")
    implementation("io.spring.gradle:dependency-management-plugin:$springDependencyManagementVersion")
}
