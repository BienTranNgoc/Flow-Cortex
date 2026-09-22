package com.flowcortex.app.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
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
 * Task 3.1 (design.md D6): {@code /v3/api-docs.yaml} is the live source of
 * the contract-first pipeline; this test both proves springdoc renders it
 * correctly on this stack and writes it to the committed {@code
 * docs/openapi.yaml} snapshot that {@code backend-ci}/{@code frontend-ci}
 * diff-check for drift (task 3.3).
 *
 * <p>Exercised against the real, fully-wired app (not a {@code @WebMvcTest}
 * slice) since the document must reflect exactly what a client would fetch —
 * including the security posture that permits it (task 3.1 lane delta).
 * Lives under {@code com.flowcortex.app} like {@code HealthController}/{@code
 * SecurityFilterChainIT} — a direct sub-package of {@code com.flowcortex}
 * would become a stray Modulith module (task 1.3's pitfall).
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
        // friction (design.md D6/Risks, issues #3169/#3268): a @Schema
        // annotation on a Java record component (HealthController.HealthResponse)
        // must actually reach the generated document, not merely compile.
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs.yaml", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Overall service health status.");
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
