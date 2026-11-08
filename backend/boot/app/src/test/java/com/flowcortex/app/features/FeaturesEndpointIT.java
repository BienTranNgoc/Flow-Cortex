package com.flowcortex.app.features;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** {@code GET /api/v1/features} answers without a token and mirrors the switches read at startup. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "flowcortex.media.upload.content-check.enabled=true")
@AutoConfigureTestRestTemplate
class FeaturesEndpointIT extends AbstractPostgresBootTest {

    record FeaturesView(Boolean emailConfirmation, Boolean phoneVerification, Boolean uploadContentCheck,
            Boolean runCancellation, Boolean credits, Boolean passwordReset, Boolean supportWidget,
            Boolean supportAssistant) {
    }

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    record Problem(int status, URI type) {
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Test
    void anonymousCallerGetsTheApplicationDefaults() {
        ResponseEntity<FeaturesView> response = restTemplate.getForEntity("/api/v1/features", FeaturesView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .isEqualTo(new FeaturesView(false, false, true, false, false, false, false, false));
    }

    @Nested
    @TestPropertySource(properties = "flowcortex.auth.password-reset.enabled=true")
    class WithPasswordResetOn {

        @Autowired
        private TestRestTemplate nestedRestTemplate;

        @Test
        void thePasswordResetSwitchReadsTrue() {
            ResponseEntity<FeaturesView> response =
                    nestedRestTemplate.getForEntity("/api/v1/features", FeaturesView.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .isEqualTo(new FeaturesView(false, false, true, false, false, true, false, false));
        }
    }

    @Test
    void withTheHelpCenterOffSupportRoutesAnswer503WhileTheAdminConsoleKeepsWorking() {
        UserView admin = userService.createInitialAdmin("features-admin-" + UUID.randomUUID() + "@example.com",
                "password123", "Admin");
        String token = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(admin.email(), "password123"), TokenResponse.class).getBody().accessToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<Problem> member = restTemplate.exchange("/api/v1/support/kb/groups", HttpMethod.GET,
                new HttpEntity<>(headers), Problem.class);
        ResponseEntity<Problem> guest = restTemplate.getForEntity("/api/v1/support/kb/groups", Problem.class);
        ResponseEntity<String> adminList = restTemplate.exchange("/api/v1/admin/support/kb/articles", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        URI disabled = URI.create("https://flow-cortex.dev/problems/support-disabled");
        assertThat(member.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(member.getBody().type()).isEqualTo(disabled);
        assertThat(guest.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(guest.getBody().type()).isEqualTo(disabled);
        assertThat(adminList.getStatusCode()).isEqualTo(HttpStatus.OK);
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
            assertThat(response.getBody())
                    .isEqualTo(new FeaturesView(false, false, true, false, true, false, false, false));
        }
    }

    @Nested
    @TestPropertySource(properties = {"flowcortex.support.enabled=true", "flowcortex.support.assistant.enabled=true"})
    class WithSupportOn {

        @Autowired
        private TestRestTemplate nestedRestTemplate;

        @Test
        void bothSupportSwitchesReadTrue() {
            ResponseEntity<FeaturesView> response =
                    nestedRestTemplate.getForEntity("/api/v1/features", FeaturesView.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .isEqualTo(new FeaturesView(false, false, true, false, false, false, true, true));
        }
    }
}
