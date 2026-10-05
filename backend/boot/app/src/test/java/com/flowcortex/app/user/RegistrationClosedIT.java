package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * With no {@code flowcortex.registration.owner-email} (the application.yml default) self-registration
 * is closed: 403 Problem Detail and no account is created.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class RegistrationClosedIT extends AbstractUserModuleIT {

    record ProblemView(int status, String detail) {
    }

    record RegisterRequest(String email, String password, String name) {
    }

    @Test
    void registerWithoutAConfiguredOwnerReturns403AndCreatesNoAccount() {
        String email = uniqueEmail("register-closed");

        ResponseEntity<ProblemView> response = restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "register-password-1", "Closed"), ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().detail()).isEqualTo("Self-registration is not available");
        assertThat(loginRaw(email, "register-password-1").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
