package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.restclient.RestTemplateBuilder;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Testcontainers;

/** With the switch off every route of the authorization server answers 503 and nothing is stored. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "flowcortex.auth.mcp-oauth.enabled=false")
@AutoConfigureTestRestTemplate
class McpOAuthSwitchOffIT extends AbstractUserModuleIT {

    @Autowired
    private JdbcTemplate jdbc;

    private TestRestTemplate client() {
        return new TestRestTemplate(new RestTemplateBuilder()
                .requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk()).rootUri(restTemplate.getRootUri()));
    }

    @Test
    void metadataRegistrationTokenAndRevocationAnswer503AndStoreNothing() {
        long clients = jdbc.queryForObject("SELECT count(*) FROM oauth_client", Long.class);
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        HttpHeaders form = new HttpHeaders();
        form.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        LinkedMultiValueMap<String, String> fields = new LinkedMultiValueMap<>();
        fields.add("grant_type", "authorization_code");

        ResponseEntity<String> metadata = client().getForEntity("/.well-known/oauth-authorization-server",
                String.class);
        ResponseEntity<String> register = client().exchange("/api/v1/oauth/register", HttpMethod.POST,
                new HttpEntity<>(Map.of("redirect_uris", List.of("https://claude.ai/api/mcp/auth_callback")), json),
                String.class);
        ResponseEntity<String> token = client().exchange("/api/v1/oauth/token", HttpMethod.POST,
                new HttpEntity<>(fields, form), String.class);
        ResponseEntity<String> revoke = client().exchange("/api/v1/oauth/revoke", HttpMethod.POST,
                new HttpEntity<>(fields, form), String.class);

        assertThat(metadata.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(register.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(token.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(revoke.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(token.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(token.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(revoke.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(revoke.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth_client", Long.class)).isEqualTo(clients);
    }
}
