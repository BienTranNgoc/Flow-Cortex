package com.flowcortex.app.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Health and metrics live on the management port, which compose never publishes; the API port
 * serves only the anonymous liveness and readiness probes and no other operational endpoint.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ManagementPortIT extends AbstractWorkflowHttpIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String[] ACTUATOR_PATHS = {"/actuator/prometheus", "/actuator/health", "/actuator"};

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Test
    void theManagementPortServesTheEngineMetricsTaggedWithTheApplication() throws Exception {
        HttpResponse<String> response = fetch(managementPort, "/actuator/prometheus", null);

        assertThat(managementPort).isNotEqualTo(serverPort);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("media_late_result_discarded", "media_jobs_dispatched")
                .contains("application=\"flow-cortex\"");
    }

    @Test
    void theManagementHealthShowsTheBrokerAndTheObjectStoreUp() throws Exception {
        HttpResponse<String> response = fetch(managementPort, "/actuator/health", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode components = JSON.readTree(response.body()).get("components");
        assertThat(components.get("rabbit").get("status").asString()).isEqualTo("UP");
        assertThat(components.get("mediaStore").get("status").asString()).isEqualTo("UP");
    }

    @Test
    void theApiPortServesNoActuatorEndpoint() throws Exception {
        for (String path : ACTUATOR_PATHS) {
            HttpResponse<String> response = fetch(serverPort, path, null);

            assertThat(response.statusCode()).as(path).isIn(401, 404);
            assertProblemWithoutOperationalData(path, response);
        }
    }

    @Test
    void anAuthenticatedCallerFindsNoActuatorEndpointOnTheApiPort() throws Exception {
        String token = loginAs(newUser("management-api-port"));

        for (String path : ACTUATOR_PATHS) {
            HttpResponse<String> response = fetch(serverPort, path, token);

            assertThat(response.statusCode()).as(path).isEqualTo(404);
            assertProblemWithoutOperationalData(path, response);
        }
    }

    @Test
    void theManagementPortServesNoApi() throws Exception {
        String token = loginAs(newUser("management-no-api"));
        assertThat(fetch(serverPort, "/api/v1/workflows", token).statusCode()).isEqualTo(200);

        HttpResponse<String> response = fetch(managementPort, "/api/v1/workflows", token);

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void theApiPortAnswersTheProbesAnonymously() throws Exception {
        assertThat(fetch(serverPort, "/readyz", null).statusCode()).isEqualTo(200);
        assertThat(fetch(serverPort, "/livez", null).statusCode()).isEqualTo(200);
    }

    private static void assertProblemWithoutOperationalData(String path, HttpResponse<String> response) {
        assertThat(response.headers().firstValue("content-type")).as(path).hasValue("application/problem+json");
        assertThat(response.body()).as(path).doesNotContain("media_", "\"components\"");
    }

    private static HttpResponse<String> fetch(int port, String path, String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
