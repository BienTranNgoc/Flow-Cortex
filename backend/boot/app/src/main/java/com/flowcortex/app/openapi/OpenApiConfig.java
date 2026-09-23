package com.flowcortex.app.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Without an explicit {@code servers} entry, springdoc auto-derives one from
 * the incoming request's scheme/host/port — which varies with {@code
 * webEnvironment = RANDOM_PORT} in tests, making the committed {@code
 * docs/openapi.yaml} snapshot non-deterministic across runs. Registering this
 * bean supplies a fixed, relative server so the generated document is stable
 * regardless of what port the app happens to bind to (matches the
 * same-origin {@code /api/} reverse-proxy shape docker-compose's nginx
 * already uses).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI flowCortexOpenApi() {
        return new OpenAPI().addServersItem(new Server().url("/"));
    }
}
