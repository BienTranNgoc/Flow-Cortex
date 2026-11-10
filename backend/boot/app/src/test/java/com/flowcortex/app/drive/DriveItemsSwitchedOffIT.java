package com.flowcortex.app.drive;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.user.api.UserView;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Default context: spaces are off, so every Drive route answers 503 and writes nothing. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class DriveItemsSwitchedOffIT extends AbstractWorkflowHttpIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void withSpacesOffEveryDriveRouteIs503() {
        UserView user = newUser("drive-off");
        String token = loginAs(user);
        Map<String, Object> item = Map.of("name", "Bio", "contentType", "text", "semanticRole", "company_bio",
                "textValue", "text");
        UUID id = UUID.randomUUID();

        assertThat(call(HttpMethod.GET, "/api/v1/drive/items", token, null)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(call(HttpMethod.POST, "/api/v1/drive/items", token, item)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(call(HttpMethod.GET, "/api/v1/drive/items/" + id, token, null))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(call(HttpMethod.PATCH, "/api/v1/drive/items/" + id, token, Map.of("name", "x")))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(call(HttpMethod.DELETE, "/api/v1/drive/items/" + id, token, null))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(call(HttpMethod.POST, "/api/v1/drive/uploads", token, Map.of("fileName", "a.png", "size", 3)))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(call(HttpMethod.GET, "/api/v1/drive/suggestions", token, null))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM drive_item WHERE owner_id = ?", Long.class, user.id()))
                .isZero();
    }

    private HttpStatus call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> response = restTemplate.exchange(path, method, new HttpEntity<>(body, headers),
                String.class);
        return HttpStatus.valueOf(response.getStatusCode().value());
    }
}
