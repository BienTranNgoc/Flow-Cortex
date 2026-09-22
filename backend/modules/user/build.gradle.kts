plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    implementation(project(":shared:web"))
    implementation(project(":shared:security"))

    // Web MVC + Spring Security/JWT types this module's OWN code needs on ITS
    // compile classpath (@RestController, @PreAuthorize, Jwt, JwtEncoder,
    // BCryptPasswordEncoder, ...). Gradle's `implementation` on a project
    // dependency exposes that project's own deps at RUNTIME only, not compile
    // time (slice 2 discovery, apply-report-slice2.md #3) — :shared:web/
    // :shared:security declare these as `implementation` themselves, so this
    // module re-declares what it uses directly rather than editing those
    // modules' build files (out of this slice's edit scope).
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
}
