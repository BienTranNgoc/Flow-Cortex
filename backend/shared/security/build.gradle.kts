plugins {
    id("flowcortex.spring-module-conventions")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-security")
    // Local HS256 JWT resource server: validates tokens this app signed (JWT_SECRET), not a remote OIDC issuer.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // HandlerExceptionResolver — the entry point / access-denied handler
    // delegate into it so :shared:web's advice renders 401/403 (this module
    // never builds a response body itself).
    implementation("org.springframework:spring-webmvc")

    // @JsonValue on Permission: the wire form of a key is its dotted string.
    implementation("com.fasterxml.jackson.core:jackson-annotations")

    // DispatcherType, for the ERROR-dispatch permitAll rule in SecurityConfig.
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
}
