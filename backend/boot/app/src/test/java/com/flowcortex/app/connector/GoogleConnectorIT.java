package com.flowcortex.app.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.connector.application.google.GoogleOAuthRules;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi.Reply;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi.TokenGrant;
import com.flowcortex.user.api.UserView;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Connecting, reading and disconnecting a Google account over real HTTP with the connector switched on. Only
 * Google is replaced: the port is a mock, while the consent URL, the sealed flow cookie, the callback chain, the
 * sealed token store and the routes are the real wiring.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.connectors.google.enabled=true",
        "flowcortex.connectors.google.client-id=it-client-id",
        "flowcortex.connectors.google.client-secret=it-client-secret",
        "flowcortex.connectors.google.redirect-uri=https://app.example/api/v1/connectors/google/callback",
        "flowcortex.workflow.reaper-interval-ms=3600000"})
@AutoConfigureTestRestTemplate
class GoogleConnectorIT extends AbstractWorkflowHttpIT {

    private static final String FLOW_COOKIE = "connector_google_flow";
    private static final String CODE = "4/it-code";
    private static final String ACCESS = "ya29.it-access-token";
    private static final String REFRESH = "1//it-refresh-token";
    private static final String EMAIL = "m@example.com";

    @MockitoBean
    private GoogleWorkspaceApi google;

    @Autowired
    private JdbcTemplate jdbc;

    /** No redirects followed and no cookie jar, so every Set-Cookie and Location stays visible. */
    private TestRestTemplate http;

    @BeforeEach
    void plainHttpClient() {
        http = new TestRestTemplate(new RestTemplateBuilder()
                .requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk())
                .redirects(HttpRedirects.DONT_FOLLOW)
                .rootUri(restTemplate.getRootUri()));
    }

    private record Flow(String cookie, String state, String url, ResponseEntity<Map> response) {
    }

    @Test
    void authorizeAnswersTheConsentUrlAndSealsTheFlowInAnHttpOnlyCookieThatHidesIt() {
        UserView member = newUser("google-authorize");

        Flow flow = authorize(loginAs(member));

        assertThat(flow.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> query = queryOf(URI.create(flow.url()));
        assertThat(flow.url()).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(query).containsEntry("client_id", "it-client-id")
                .containsEntry("redirect_uri", "https://app.example/api/v1/connectors/google/callback")
                .containsEntry("response_type", "code").containsEntry("access_type", "offline")
                .containsEntry("prompt", "consent").containsEntry("include_granted_scopes", "true")
                .containsEntry("code_challenge_method", "S256").containsEntry("scope",
                        String.join(" ", GoogleOAuthRules.SCOPES))
                .containsKeys("state", "code_challenge");
        String header = setCookieHeader(flow.response(), FLOW_COOKIE);
        assertThat(header).contains("HttpOnly", "Secure", "SameSite=Lax", "Max-Age=600",
                "Path=/api/v1/connectors/google/callback");
        String sealed = new String(Base64.getDecoder().decode(flow.cookie().split("\\.", -1)[3]),
                StandardCharsets.ISO_8859_1);
        assertThat(flow.cookie() + sealed).doesNotContain(member.id().toString()).doesNotContain(flow.state());
        assertThat(flow.response().getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(http.exchange("/api/v1/connectors/google/authorize", HttpMethod.POST, HttpEntity.EMPTY,
                Void.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aValidCallbackStoresTheSealedConnectionReadsBackWithoutATokenAndDisconnectRevokesAndDeletes() {
        UserView member = newUser("google-connect");
        String token = loginAs(member);
        googleGrants();
        Flow flow = authorize(token);

        ResponseEntity<Void> callback = callback(flow.cookie(), flow.state(), CODE);

        assertThat(callback.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(callback.getHeaders().getLocation())
                .hasToString("/integrations?connector=google&result=connected");
        assertThat(setCookieHeader(callback, FLOW_COOKIE)).contains("Max-Age=0");
        assertThat(callback.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(callback.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM connector_auth WHERE user_id = ? "
                + "AND provider = 'google'", member.id());
        assertThat(row.get("access_token").toString()).isNotBlank().doesNotContain(ACCESS);
        assertThat(row.get("refresh_token").toString()).isNotBlank().doesNotContain(REFRESH);
        assertThat(row.get("metadata").toString()).contains(EMAIL);
        assertThat(row.get("is_active")).isEqualTo(true);

        ResponseEntity<Map> read = restTemplate.exchange("/api/v1/connectors/google", HttpMethod.GET,
                bearer(token), Map.class);

        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody()).containsOnlyKeys("provider", "email", "scopes", "missingScopes", "expiresAt",
                "usageEnabled").containsEntry("email", EMAIL).containsEntry("missingScopes", List.of());
        assertThat(String.valueOf(read.getBody())).doesNotContain(ACCESS).doesNotContain(REFRESH);

        ResponseEntity<Void> removed = restTemplate.exchange("/api/v1/connectors/google", HttpMethod.DELETE,
                bearer(token), Void.class);

        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(google, times(1)).revoke(REFRESH);
        assertThat(googleRows(member)).isZero();
    }

    @Test
    void aSuspendedMemberWithAStillValidTokenIsRefusedWith403OnDisconnectAndTheConnectionStays() {
        UserView owner = newUser("google-suspend-owner");
        UserView member = userService.createUser(owner.id(), uniqueEmail("google-suspend"), "password123",
                "Soon Suspended", "MEMBER");
        String token = loginAs(member);
        googleGrants();
        Flow flow = authorize(token);
        callback(flow.cookie(), flow.state(), CODE);
        assertThat(googleRows(member)).isEqualTo(1);

        userService.updateUser(owner.id(), member.id(), null, null, null, "SUSPENDED");
        ResponseEntity<Void> removed = restTemplate.exchange("/api/v1/connectors/google", HttpMethod.DELETE,
                bearer(token), Void.class);

        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(googleRows(member)).isEqualTo(1);
        verify(google, never()).revoke(anyString());
    }

    @Test
    void aCallbackWithoutTheFlowCookieIsInvalidStateAnswersWithoutATokenAndStoresNothing() {
        UserView member = newUser("google-no-cookie");
        Flow flow = authorize(loginAs(member));

        ResponseEntity<Void> callback = callback(null, flow.state(), CODE);

        assertThat(callback.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(callback.getHeaders().getLocation())
                .hasToString("/integrations?connector=google&result=invalid_state");
        assertThat(setCookieHeader(callback, FLOW_COOKIE)).contains("Max-Age=0");
        assertThat(googleRows(member)).isZero();
        verify(google, never()).exchangeCode(anyString(), anyString());
    }

    @Test
    void aCallbackWithAnotherStateIsInvalidStateAndNothingReachesGoogle() {
        UserView member = newUser("google-state-swap");
        Flow flow = authorize(loginAs(member));

        ResponseEntity<Void> callback = callback(flow.cookie(), flow.state() + "x", CODE);

        assertThat(callback.getHeaders().getLocation())
                .hasToString("/integrations?connector=google&result=invalid_state");
        assertThat(googleRows(member)).isZero();
        verify(google, never()).exchangeCode(anyString(), anyString());
    }

    @Test
    void theOtherGoogleRoutesStillNeedAToken() {
        assertThat(http.exchange("/api/v1/connectors/google", HttpMethod.GET, HttpEntity.EMPTY, Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.exchange("/api/v1/connectors/google", HttpMethod.DELETE, HttpEntity.EMPTY, Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private void googleGrants() {
        when(google.exchangeCode(eq(CODE), anyString())).thenReturn(new TokenGrant(200, ACCESS, REFRESH, 3599,
                GoogleOAuthRules.SCOPES, null, null));
        when(google.userinfo(any())).thenReturn(new Reply(200, Map.of("email", EMAIL), null, null));
    }

    private int googleRows(UserView member) {
        return jdbc.queryForObject("SELECT count(*) FROM connector_auth WHERE user_id = ? AND provider = 'google'",
                Integer.class, member.id());
    }

    private Flow authorize(String token) {
        ResponseEntity<Map> response = http.exchange("/api/v1/connectors/google/authorize", HttpMethod.POST,
                bearer(token), Map.class);
        String url = response.getBody() == null ? null : (String) response.getBody().get("authorizationUrl");
        String state = url == null ? null : queryOf(URI.create(url)).get("state");
        return new Flow(cookieValue(response, FLOW_COOKIE), state, url, response);
    }

    private ResponseEntity<Void> callback(String cookie, String state, String code) {
        HttpHeaders headers = new HttpHeaders();
        if (cookie != null) {
            headers.set(HttpHeaders.COOKIE, FLOW_COOKIE + "=" + cookie);
        }
        return http.exchange("/api/v1/connectors/google/callback?code=" + code + "&state=" + state, HttpMethod.GET,
                new HttpEntity<>(headers), Void.class);
    }

    private static HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private static Map<String, String> queryOf(URI uri) {
        Map<String, String> query = new HashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            query.put(kv[0], URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        return query;
    }

    private static String setCookieHeader(ResponseEntity<?> response, String name) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        return cookies == null ? null
                : cookies.stream().filter(cookie -> cookie.startsWith(name + "=")).findFirst().orElse(null);
    }

    private static String cookieValue(ResponseEntity<?> response, String name) {
        String header = setCookieHeader(response, name);
        return header == null ? null : header.substring(name.length() + 1, header.indexOf(';'));
    }
}
