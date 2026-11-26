package com.flowcortex.app.entity;

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

/** Default context: spaces are off, so every entity route answers 503 and writes nothing. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class EntityApiSwitchedOffIT extends AbstractWorkflowHttpIT {

    private static final String ENTITIES = "/api/v1/entities";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void withSpacesOffEveryEntityRouteIs503() {
        UserView user = newUser("entity-off");
        String token = loginAs(user);
        UUID id = UUID.randomUUID();

        assertThat(call(HttpMethod.GET, ENTITIES, token, null)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(call(HttpMethod.POST, ENTITIES, token, Map.of("kind", "brand", "name", "Nodi")))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(call(HttpMethod.GET, ENTITIES + "/" + id, token, null)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(call(HttpMethod.PATCH, ENTITIES + "/" + id, token, Map.of("baseVersion", 1, "name", "x")))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(call(HttpMethod.DELETE, ENTITIES + "/" + id, token, null)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(call(HttpMethod.GET, ENTITIES + "/tags", token, null)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(call(HttpMethod.GET, ENTITIES + "/categories", token, null))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(call(HttpMethod.GET, ENTITIES + "/labels", token, null)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM space_entity WHERE created_by = ?", Long.class,
                user.id())).isZero();
    }

    private HttpStatus call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> response = restTemplate.exchange(path, method, new HttpEntity<>(body, headers),
                String.class);
        return HttpStatus.valueOf(response.getStatusCode().value());
    }
}
