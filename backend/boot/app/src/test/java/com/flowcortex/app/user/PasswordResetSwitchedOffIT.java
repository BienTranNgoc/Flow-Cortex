package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/** With the password-reset switch off, both endpoints answer 503 before reading the body and send nothing. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "flowcortex.auth.password-reset.enabled=false")
@AutoConfigureTestRestTemplate
@Import(PasswordResetSwitchedOffIT.Mail.class)
class PasswordResetSwitchedOffIT extends AbstractUserModuleIT {

    @Autowired
    private CapturingMailSender mail;

    record ProblemView(int status, String detail) {
    }

    @Test
    void bothEndpointsAnswer503AndNoMailIsSent() {
        String email = uniqueEmail("switched-off");
        userService.createInitialAdmin(email, "old-password-1", "Switched Off");

        ResponseEntity<ProblemView> forgot = restTemplate.postForEntity("/api/v1/auth/forgot-password",
                Map.of("email", email), ProblemView.class);
        ResponseEntity<ProblemView> reset = restTemplate.postForEntity("/api/v1/auth/reset-password",
                Map.of("token", "any-token", "password", "NewSecret8"), ProblemView.class);
        ResponseEntity<ProblemView> malformed = restTemplate.postForEntity("/api/v1/auth/forgot-password",
                Map.of("email", "an.example.com"), ProblemView.class);

        assertThat(List.of(forgot, reset, malformed)).allSatisfy(response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody().detail()).isEqualTo("This feature is turned off");
        });
        assertThat(mail.sentTo(email)).isEmpty();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Mail {

        @Bean
        CapturingMailSender capturingMailSender(ObjectProvider<ThreadPoolTaskExecutor> executor) {
            return new CapturingMailSender(executor);
        }
    }
}
