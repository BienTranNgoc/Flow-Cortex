package com.flowcortex.app.trash;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The Trash routes answer 503 with the feature-turned-off text, in the request's language, while the switch is off,
 * even for a body that cannot be read and a parameter that is not a number.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.trash.enabled=false")
@AutoConfigureTestRestTemplate
class TrashListSwitchedOffIT extends AbstractPostgresBootTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    record LoginRequest(String email, String password) {
    }

    record Token(String accessToken) {
    }

    record Problem(int status, String detail) {
    }

    @Test
    void switchedOffAnswers503() {
        UserView member = userService.createInitialAdmin("trash-off-" + UUID.randomUUID() + "@example.com",
                "password123", "trash-off");
        String token = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(member.email(), "password123"), Token.class).getBody().accessToken();

        ResponseEntity<Problem> english = list(token, "en");
        ResponseEntity<Problem> vietnamese = list(token, "vi");

        assertThat(english.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(english.getBody().detail()).isEqualTo("This feature is turned off");
        assertThat(vietnamese.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(vietnamese.getBody().detail()).isNotEqualTo(english.getBody().detail());
    }

    @Test
    void anUnreadableBodyAnswers503BeforeAnyRuleCheck() {
        UserView member = userService.createInitialAdmin("trash-off-" + UUID.randomUUID() + "@example.com",
                "password123", "trash-off");
        String token = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(member.email(), "password123"), Token.class).getBody().accessToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        for (String route : new String[] {"restore", "purge", "empty"}) {
            ResponseEntity<Problem> answer = restTemplate.exchange("/api/v1/trash/" + route, HttpMethod.POST,
                    new HttpEntity<>("{\"items\":[", headers), Problem.class);

            assertThat(answer.getStatusCode()).as(route).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(answer.getBody().detail()).as(route).isEqualTo("This feature is turned off");
        }
    }

    @Test
    void aParameterThatIsNotANumberAnswers503() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(signIn());

        for (String query : new String[] {"page=abc", "size=x", "page=abc&size=x"}) {
            ResponseEntity<Problem> answer = restTemplate.exchange("/api/v1/trash?" + query, HttpMethod.GET,
                    new HttpEntity<>(headers), Problem.class);

            assertThat(answer.getStatusCode()).as(query).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(answer.getBody().detail()).as(query).isEqualTo("This feature is turned off");
        }
    }

    @Test
    void aCallWithoutASignInStillAnswers401() {
        ResponseEntity<Problem> answer = restTemplate.getForEntity("/api/v1/trash?page=abc", Problem.class);

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String signIn() {
        UserView member = userService.createInitialAdmin("trash-off-" + UUID.randomUUID() + "@example.com",
                "password123", "trash-off");
        return restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(member.email(), "password123"), Token.class).getBody().accessToken();
    }

    private ResponseEntity<Problem> list(String token, String language) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
        return restTemplate.exchange("/api/v1/trash?type={type}", HttpMethod.GET, new HttpEntity<>(headers),
                Problem.class, Map.of("type", "demo"));
    }
}
