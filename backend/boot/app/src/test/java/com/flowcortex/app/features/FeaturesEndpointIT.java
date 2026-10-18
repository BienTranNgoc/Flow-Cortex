package com.flowcortex.app.features;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** {@code GET /api/v1/features} answers without a token and mirrors the switches read at startup. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "flowcortex.media.upload.content-check.enabled=true")
@AutoConfigureTestRestTemplate
class FeaturesEndpointIT extends AbstractPostgresBootTest {

    record FeaturesView(Boolean emailConfirmation, Boolean phoneVerification, Boolean uploadContentCheck,
            Boolean runCancellation, Boolean credits) {
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void anonymousCallerGetsTheApplicationDefaults() {
        ResponseEntity<FeaturesView> response = restTemplate.getForEntity("/api/v1/features", FeaturesView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new FeaturesView(false, false, true, false, false));
    }

    @Nested
    @TestPropertySource(properties = "flowcortex.credits.enabled=true")
    class WithCreditsOn {

        @Autowired
        private TestRestTemplate nestedRestTemplate;

        @Test
        void theCreditsSwitchReadsTrue() {
            ResponseEntity<FeaturesView> response =
                    nestedRestTemplate.getForEntity("/api/v1/features", FeaturesView.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(new FeaturesView(false, false, true, false, true));
        }
    }
}
