package com.flowcortex.shared.web;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CORS properties. The defaults match the frontend dev server
 * (Vite, port 5173). The remember-me cookie requires
 * {@code allowCredentials = true}, which forces the origin to be a
 * literal value (not {@code *}).
 */
@ConfigurationProperties("flowcortex.security.cors")
public record CorsProperties(List<String> allowedOrigins, List<String> allowedMethods, List<String> allowedHeaders,
        List<String> exposedHeaders, boolean allowCredentials) {

    public CorsProperties {
        if (allowedOrigins == null) {
            allowedOrigins = List.of("http://localhost:5173");
        }
        if (allowedMethods == null) {
            allowedMethods = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        }
        if (allowedHeaders == null) {
            allowedHeaders = List.of("*");
        }
        if (exposedHeaders == null) {
            exposedHeaders = List.of("X-Request-Id");
        }
        if (allowCredentials && allowedOrigins.contains("*")) {
            throw new IllegalStateException(
                    "flowcortex.security.cors.allowed-origins must list literal origins when allow-credentials is true");
        }
    }
}
