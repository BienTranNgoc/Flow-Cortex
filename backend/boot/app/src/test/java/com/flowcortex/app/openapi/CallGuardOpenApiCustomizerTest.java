package com.flowcortex.app.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

class CallGuardOpenApiCustomizerTest {

    private final CallGuardOpenApiCustomizer customizer = new CallGuardOpenApiCustomizer();

    private static Operation operation() {
        return new Operation().responses(new ApiResponses().addApiResponse("200", new ApiResponse()));
    }

    private OpenAPI customised(OpenAPI document) {
        customizer.customise(document);
        return document;
    }

    private static OpenAPI document(String route, PathItem item) {
        return new OpenAPI().paths(new Paths().addPathItem(route, item));
    }

    @Test
    void aKeyedWriteDocumentsTheKeyTheReplayHeaderAndEveryGuardAnswer() {
        Operation post = operation();
        customised(document("/api/v1/workflows", new PathItem().post(post)));

        assertThat(post.getParameters()).extracting(Parameter::get$ref)
                .containsExactly("#/components/parameters/IdempotencyKey");
        assertThat(post.getResponses().get("200").getHeaders().get("Idempotent-Replayed").get$ref())
                .isEqualTo("#/components/headers/IdempotentReplayed");
        assertThat(post.getResponses()).extractingByKeys("400", "409", "422", "429")
                .extracting(ApiResponse::get$ref)
                .containsExactly("#/components/responses/IdempotencyKeyInvalid",
                        "#/components/responses/IdempotencyInProgress",
                        "#/components/responses/IdempotencyKeyReused",
                        "#/components/responses/ApiTokenDailyLimit");
    }

    @Test
    void aPatchIsKeyedToo() {
        Operation patch = operation();
        customised(document("/api/v1/workflows/{id}", new PathItem().patch(patch)));

        assertThat(patch.getParameters()).hasSize(1);
        assertThat(patch.getResponses()).containsKeys("400", "409", "422", "429");
    }

    @Test
    void aPutOrDeleteOnlyCountsAgainstTheDailyLimit() {
        Operation put = operation();
        Operation delete = operation();
        customised(document("/api/v1/runs/{id}", new PathItem().put(put).delete(delete)));

        for (Operation write : new Operation[] {put, delete}) {
            assertThat(write.getParameters()).isNull();
            assertThat(write.getResponses().get("200").getHeaders()).isNull();
            assertThat(write.getResponses().keySet()).containsExactlyInAnyOrder("200", "429");
        }
    }

    @Test
    void aReadOrARouteATokenCannotReachIsLeftAlone() {
        Operation read = operation();
        Operation outside = operation();
        OpenAPI document = new OpenAPI().paths(new Paths()
                .addPathItem("/api/v1/workflows", new PathItem().get(read))
                .addPathItem("/api/v1/me/password", new PathItem().put(outside)));
        customised(document);

        for (Operation untouched : new Operation[] {read, outside}) {
            assertThat(untouched.getParameters()).isNull();
            assertThat(untouched.getResponses().keySet()).containsExactly("200");
            assertThat(untouched.getResponses().get("200").getHeaders()).isNull();
        }
    }

    @Test
    void aDeclaredKeyOrAnswerIsNotReplaced() {
        Operation post = operation().addParametersItem(new Parameter().name("Idempotency-Key").in("header"));
        ApiResponse conflict = new ApiResponse().description("A workflow with this name exists");
        post.getResponses().addApiResponse("409", conflict);
        customised(document("/api/v1/workflows", new PathItem().post(post)));

        assertThat(post.getParameters()).hasSize(1);
        assertThat(post.getResponses().get("409")).isSameAs(conflict);
    }
}
