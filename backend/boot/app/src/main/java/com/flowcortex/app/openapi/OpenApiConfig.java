package com.flowcortex.app.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Without an explicit {@code servers} entry, springdoc derives one from the request's
 * scheme/host/port — which varies with {@code RANDOM_PORT} in tests, making the committed
 * {@code docs/openapi.yaml} snapshot non-deterministic. This bean fixes a relative server so
 * the doc is stable (matches nginx's same-origin {@code /api/} reverse-proxy shape).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI flowCortexOpenApi() {
        return new OpenAPI().addServersItem(new Server().url("/"));
    }
}
