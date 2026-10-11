package com.flowcortex.shared.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration for the API. Applied to {@code /api/v1/**} only. The
 * remember-me cookie flow requires {@code allowCredentials = true}, which
 * means the origin must be a literal value (a list of one or more
 * configured origins, not a wildcard).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig implements WebMvcConfigurer {

    private final CorsProperties properties;

    public CorsConfig(CorsProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/v1/**")
                .allowedOrigins(properties.allowedOrigins().toArray(new String[0]))
                .allowedMethods(properties.allowedMethods().toArray(new String[0]))
                .allowedHeaders(properties.allowedHeaders().toArray(new String[0]))
                .exposedHeaders(properties.exposedHeaders().toArray(new String[0]))
                .allowCredentials(properties.allowCredentials());
    }
}
