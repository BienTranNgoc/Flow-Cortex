package com.flowcortex.app.openapi;

import com.flowcortex.user.apicall.ApiTokenCallGuards;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * Attaches the personal access token call guards to the operations they act on: the optional
 * {@code Idempotency-Key} header and the answers it can cause on the keyed writes, and the daily
 * limit on every write. The routes come from {@link ApiTokenCallGuards}, the predicate the guard itself
 * is built from. A response an operation already declares for a status is left as it is.
 */
@Component
class CallGuardOpenApiCustomizer implements GlobalOpenApiCustomizer {

    static final String IDEMPOTENCY_KEY = "IdempotencyKey";
    static final String IDEMPOTENT_REPLAYED = "IdempotentReplayed";
    static final String KEY_INVALID = "IdempotencyKeyInvalid";
    static final String IN_PROGRESS = "IdempotencyInProgress";
    static final String KEY_REUSED = "IdempotencyKeyReused";
    static final String DAILY_LIMIT = "ApiTokenDailyLimit";

    private static final String KEY_HEADER = "Idempotency-Key";
    private static final String REPLAYED_HEADER = "Idempotent-Replayed";

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().forEach((route, item) -> item.readOperationsMap().forEach((method, operation) -> {
            String verb = method.name();
            if (ApiTokenCallGuards.takesIdempotencyKey(verb, route)) {
                documentKey(operation);
            }
            if (ApiTokenCallGuards.countsAgainstDailyLimit(verb, route)) {
                documentLimit(operation);
            }
        }));
    }

    private static void documentKey(Operation operation) {
        boolean declared = operation.getParameters() != null && operation.getParameters().stream()
                .anyMatch(p -> KEY_HEADER.equalsIgnoreCase(p.getName())
                        || (p.get$ref() != null && p.get$ref().endsWith("/" + IDEMPOTENCY_KEY)));
        if (!declared) {
            operation.addParametersItem(new Parameter().$ref(IDEMPOTENCY_KEY));
        }
        ApiResponses responses = responses(operation);
        responses.forEach((status, response) -> {
            if (status.startsWith("2") && response.get$ref() == null
                    && (response.getHeaders() == null || !response.getHeaders().containsKey(REPLAYED_HEADER))) {
                response.addHeaderObject(REPLAYED_HEADER, new Header().$ref(IDEMPOTENT_REPLAYED));
            }
        });
        addIfAbsent(responses, "400", KEY_INVALID);
        addIfAbsent(responses, "409", IN_PROGRESS);
        addIfAbsent(responses, "422", KEY_REUSED);
    }

    private static void documentLimit(Operation operation) {
        addIfAbsent(responses(operation), "429", DAILY_LIMIT);
    }

    private static ApiResponses responses(Operation operation) {
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        return operation.getResponses();
    }

    private static void addIfAbsent(ApiResponses responses, String status, String component) {
        if (!responses.containsKey(status)) {
            responses.addApiResponse(status, new ApiResponse().$ref(component));
        }
    }
}
