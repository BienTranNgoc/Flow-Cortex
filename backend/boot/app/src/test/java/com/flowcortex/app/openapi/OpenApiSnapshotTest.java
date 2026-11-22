package com.flowcortex.app.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowcortex.AbstractPostgresBootTest;
import io.swagger.v3.core.util.Yaml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

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
