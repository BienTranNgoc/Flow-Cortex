package com.flowcortex.app.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info().title("FlowCortex API").version("v1.0"))
            // Preserve the root server to avoid random port drift in OpenAPI snapshot test
            .addServersItem(new Server().url("/"))
            .addSecurityItem(new SecurityRequirement().addList("BearerAuth"))
            .components(new Components()
                .addSecuritySchemes("BearerAuth", new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT"))
                .addParameters(CallGuardOpenApiCustomizer.IDEMPOTENCY_KEY, idempotencyKey())
                .addHeaders(CallGuardOpenApiCustomizer.IDEMPOTENT_REPLAYED, new Header()
                    .description("true when the answer is the stored one of an earlier request with the same key")
                    .schema(new StringSchema()._enum(List.of("true"))))
                .addResponses(CallGuardOpenApiCustomizer.KEY_INVALID, problem("The Idempotency-Key header is blank"
                    + " or longer than 80 characters, or the keyed request body cannot be fingerprinted (multipart"
                    + " or over 1 MiB).", null))
                .addResponses(CallGuardOpenApiCustomizer.IN_PROGRESS,
                    problem("A request with the same key is still running.", null))
                .addResponses(CallGuardOpenApiCustomizer.KEY_REUSED, problem("The key was used with a different"
                    + " method, path, query or body.", null))
                .addResponses(CallGuardOpenApiCustomizer.DAILY_LIMIT, problem("The token owner's daily write limit"
                    + " is reached (code API_TOKEN_DAILY_LIMIT); retry after the UTC day ends.",
                    new Header().description("Seconds until the next UTC day").schema(new StringSchema()))));
    }

    /** Documented once here and attached to the guarded operations by {@link CallGuardOpenApiCustomizer}. */
    private static Parameter idempotencyKey() {
        return new Parameter()
            .name("Idempotency-Key")
            .in("header")
            .required(false)
            .description("Optional on POST and PATCH made with a personal access token. A repeat of the same"
                + " request within 24 hours returns the stored answer with Idempotent-Replayed: true.")
            .schema(new StringSchema().minLength(1).maxLength(80));
    }

    private static ApiResponse problem(String description, Header retryAfter) {
        ApiResponse response = new ApiResponse().description(description).content(new Content().addMediaType(
            "application/problem+json",
            new MediaType().schema(new Schema<>().$ref("#/components/schemas/ProblemDetail"))));
        if (retryAfter != null) {
            response.addHeaderObject("Retry-After", retryAfter);
        }
        return response;
    }
}
