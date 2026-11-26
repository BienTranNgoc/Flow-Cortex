package com.flowcortex.app.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;

/** Default context: spaces are off, so every library route answers 503 before it reads anything. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AssetLibrarySwitchedOffIT extends AbstractWorkflowHttpIT {

    @Test
    void spacesOffIs503() {
        String token = loginAs(newUser("assets-off"));

        for (String path : List.of("", "/groups", "/usage", "/tags", "/" + UUID.randomUUID(), "?kind=pdf")) {
            assertThat(get("/api/v1/assets" + path, token, String.class).getStatusCode())
                    .as(path).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
