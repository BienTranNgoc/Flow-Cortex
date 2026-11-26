package com.flowcortex.app.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowcortex.AbstractPostgresBootTest;
import io.swagger.v3.core.util.Yaml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code /v3/api-docs.yaml} is the live source of the contract-first pipeline; this test proves
 * springdoc renders it correctly and writes it to the committed {@code docs/openapi.yaml}
 * snapshot that {@code backend-ci}/{@code frontend-ci} diff-check for drift.
 *
 * <p>Exercised against the real, fully-wired app (not a {@code @WebMvcTest} slice) since the
 * document must reflect exactly what a client would fetch, including its security posture.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class OpenApiSnapshotTest extends AbstractPostgresBootTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern COLLISION_SUFFIX = Pattern.compile("_\\d+$");

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void jacksonThreeSchemaAnnotationSurvivesGeneration() {
        // Smoke test for springdoc's early-Boot-4/Jackson-3 introspection
        // friction (issues #3169/#3268): a @Schema annotation on a Java
        // record component (HealthController.HealthResponse) must actually
        // reach the generated document, not merely compile.
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs.yaml", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Overall service health status.");
    }

    @Test
    void googleConnectorRoutesPublishTheirSuccessBody() {
        // An explicit error @ApiResponse makes springdoc drop its default 200, which leaves the generated
        // client with an error-only return type.
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        tools.jackson.databind.JsonNode paths = JSON.readTree(response.getBody()).path("paths");

        Set<String> bodyOperations = Set.of("googleAuthorize", "googleRead", "googleHealth", "listGoogleSpreadsheets",
                "listGoogleSpreadsheetTabs", "previewGoogleSpreadsheetTab");
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, tools.jackson.databind.JsonNode> path : paths.properties()) {
            if (!path.getKey().startsWith("/api/v1/connectors/google")) {
                continue;
            }
            for (Map.Entry<String, tools.jackson.databind.JsonNode> operation : path.getValue().properties()) {
                tools.jackson.databind.JsonNode responses = operation.getValue().path("responses");
                String operationId = operation.getValue().path("operationId").asString();
                assertThat(responses.propertyNames().stream().anyMatch(code -> code.startsWith("2") || code.startsWith("3")))
                        .as("%s %s documents a success response", operation.getKey(), path.getKey())
                        .isTrue();
                if (bodyOperations.contains(operationId)) {
                    seen.add(operationId);
                    tools.jackson.databind.JsonNode content = responses.path("200").path("content");
                    assertThat(content.isObject() && content.properties().stream()
                            .anyMatch(media -> media.getValue().path("schema").isObject()))
                            .as("%s documents a 200 body schema", operationId)
                            .isTrue();
                }
            }
        }
        assertThat(seen).isEqualTo(bodyOperations);
    }

    @Test
    void theWorkflowArtifactPublishesAStepConfigAsAnObjectAndTheOptionalDataStore() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        tools.jackson.databind.JsonNode schemas = JSON.readTree(response.getBody()).at("/components/schemas");

        tools.jackson.databind.JsonNode config = schemas.at("/WorkflowArtifactStep/properties/config");
        assertThat(config.get("type").toString()).contains("object").contains("null").doesNotContain("string");
        assertThat(schemas.at("/WorkflowArtifactStep/properties/stepOrder/maximum").asInt()).isEqualTo(32767);
        assertThat(schemas.at("/WorkflowArtifact/properties/dataStore/items/$ref").asText())
                .endsWith("/WorkflowArtifactDataEntry");
        assertThat(schemas.at("/WorkflowArtifact/required").toString()).doesNotContain("dataStore");
        assertThat(schemas.at("/WorkflowArtifactDataEntry/required").toString()).contains("key", "dataType", "value");
    }

    @Test
    void theOAuthErrorAnswersAreTypedAsTheOAuthErrorObject() throws IOException {
        JsonNode paths = liveDocument().get("paths");
        String[][] errorAnswers = {
                {"/api/v1/oauth/token", "400", "401", "429"},
                {"/api/v1/oauth/register", "400", "429"},
                {"/api/v1/oauth/revoke", "400", "401", "429"}};

        for (String[] route : errorAnswers) {
            for (int i = 1; i < route.length; i++) {
                JsonNode response = paths.at(pointer(route[0]) + "/post/responses/" + route[i]);
                assertThat(response.at("/content/application~1json/schema/$ref").asText())
                        .as("%s %s", route[0], route[i])
                        .isEqualTo("#/components/schemas/OAuthError");
            }
        }
    }

    @Test
    void theCallGuardsAreDocumentedOnTheGuardedOperationsOnly() throws IOException {
        JsonNode paths = liveDocument().get("paths");

        JsonNode guarded = paths.at(pointer("/api/v1/workflows") + "/post");
        assertThat(guarded.get("parameters").findValuesAsText("$ref"))
                .contains("#/components/parameters/IdempotencyKey");
        assertThat(guarded.at("/responses/409/$ref").asText()).isEqualTo("#/components/responses/IdempotencyInProgress");
        assertThat(guarded.at("/responses/429/$ref").asText()).isEqualTo("#/components/responses/ApiTokenDailyLimit");
        assertThat(guarded.at("/responses/201/headers/Idempotent-Replayed/$ref").asText())
                .isEqualTo("#/components/headers/IdempotentReplayed");

        String read = paths.at(pointer("/api/v1/workflows") + "/get").toString();
        assertThat(read).doesNotContain("IdempotencyKey", "IdempotentReplayed", "ApiTokenDailyLimit");
        String outsideTheTokenRoutes = paths.at(pointer("/api/v1/oauth/token") + "/post").toString();
        assertThat(outsideTheTokenRoutes).doesNotContain("IdempotencyKey", "ApiTokenDailyLimit");
    }

    private JsonNode liveDocument() throws IOException {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs.yaml", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Yaml.mapper().readTree(response.getBody());
    }

    private static String pointer(String path) {
        return "/" + path.replace("~", "~0").replace("/", "~1");
    }

    @Test
    void noOperationIdCarriesAnAutoGeneratedCollisionSuffix() {
        // springdoc suffixes colliding method names (list, list_1, ...) in scan order, so the generated
        // client's functions silently re-point when a controller changes. Colliding methods need an
        // explicit @Operation(operationId = ...).
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<String> suffixed = new ArrayList<>();
        for (Map.Entry<String, tools.jackson.databind.JsonNode> path
                : JSON.readTree(response.getBody()).get("paths").properties()) {
            for (Map.Entry<String, tools.jackson.databind.JsonNode> operation : path.getValue().properties()) {
                tools.jackson.databind.JsonNode operationId = operation.getValue().get("operationId");
                if (operationId != null && COLLISION_SUFFIX.matcher(operationId.asString()).find()) {
                    suffixed.add(operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey()
                            + " -> " + operationId.asString());
                }
            }
        }

        assertThat(suffixed)
                .as("operations with an auto-generated _<n> operationId suffix; pin each with"
                        + " @Operation(operationId = ...)")
                .isEmpty();
    }

    @Test
    void writesOpenApiSnapshotToTheCommittedFile() throws IOException {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs.yaml", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        String document = response.getBody();
        assertThat(document).isNotBlank();

        String docsFileProperty = System.getProperty("flowcortex.openapi.docsFile");
        assertThat(docsFileProperty)
                .as("flowcortex.openapi.docsFile system property must be set by :boot:app's Test task"
                        + " (see backend/boot/app/build.gradle.kts)")
                .isNotBlank();

        Path snapshotPath = Path.of(docsFileProperty);
        Files.createDirectories(snapshotPath.getParent());
        Files.writeString(snapshotPath, document, StandardCharsets.UTF_8);
    }
}
