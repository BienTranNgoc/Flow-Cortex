package com.flowcortex.app.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
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
        JsonNode paths = JsonMapper.builder().build().readTree(response.getBody()).path("paths");

        Set<String> bodyOperations = Set.of("googleAuthorize", "googleRead", "googleHealth", "listGoogleSpreadsheets",
                "listGoogleSpreadsheetTabs", "previewGoogleSpreadsheetTab");
        Set<String> seen = new HashSet<>();
        for (Entry<String, JsonNode> path : paths.properties()) {
            if (!path.getKey().startsWith("/api/v1/connectors/google")) {
                continue;
            }
            for (Entry<String, JsonNode> operation : path.getValue().properties()) {
                JsonNode responses = operation.getValue().path("responses");
                String operationId = operation.getValue().path("operationId").asString();
                assertThat(responses.propertyNames().stream().anyMatch(code -> code.startsWith("2") || code.startsWith("3")))
                        .as("%s %s documents a success response", operation.getKey(), path.getKey())
                        .isTrue();
                if (bodyOperations.contains(operationId)) {
                    seen.add(operationId);
                    JsonNode content = responses.path("200").path("content");
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
