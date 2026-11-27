package com.flowcortex.app.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi;
import com.flowcortex.user.api.UserView;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The default stack: no Google setting at all, so the connector is off and no request leaves for Google. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.workflow.reaper-interval-ms=3600000"})
@AutoConfigureTestRestTemplate
class GoogleConnectorOffIT extends AbstractWorkflowHttpIT {

    @MockitoBean
    private GoogleWorkspaceApi google;

    @Test
    void authorizeAnswers503TurnedOffAndNoRequestReachesGoogle() {
        UserView member = newUser("google-off");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(loginAs(member));
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en");

        ResponseEntity<Map> response = restTemplate.exchange("/api/v1/connectors/google/authorize",
                HttpMethod.POST, new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("code", "TURNED_OFF")
                .containsEntry("detail", "This feature is turned off");
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNull();
        verifyNoInteractions(google);
    }
}
