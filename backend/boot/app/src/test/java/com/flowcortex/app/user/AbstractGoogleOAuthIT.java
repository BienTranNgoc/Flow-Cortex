package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

import com.flowcortex.user.application.GoogleIdTokenClaims;
import com.flowcortex.user.application.GoogleOAuthClient;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Drives the Google sign-in over real HTTP. Only Google itself is stubbed: the spy answers the
 * code exchange with the claims a test chooses, everything else (authorize URL, flow cookie,
 * callback, user resolution, session cookie) is the real wiring.
 */
abstract class AbstractGoogleOAuthIT extends AbstractUserModuleIT {

    protected static final String CODE = "google-code";
    protected static final String FLOW_COOKIE = "oauth_flow";
    protected static final String REFRESH_COOKIE = "refresh_token";
    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger(1);

    @MockitoSpyBean
    protected GoogleOAuthClient google;

    @Autowired
    protected JdbcTemplate jdbc;

    /** No redirects followed and no cookie jar, so every Set-Cookie and Location stays visible. */
    protected TestRestTemplate http;

    protected record Flow(String cookie, String state, ResponseEntity<Void> response) {
    }

    @BeforeEach
    void plainHttpClient() {
        http = new TestRestTemplate(new RestTemplateBuilder()
                .requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk())
                .redirects(HttpRedirects.DONT_FOLLOW)
                .rootUri(restTemplate.getRootUri()));
    }

    protected static String newClientIp() {
        return "198.51.100." + NEXT_CLIENT.getAndIncrement();
    }

    protected void googleKnows(String sub, String email) {
        doReturn(new GoogleIdTokenClaims(sub, email, true)).when(google)
                .exchangeAndVerify(eq(CODE), anyString(), anyString());
    }

    protected Flow authorize(String returnTo, boolean rememberMe, String clientIp) {
        String query = "?remember_me=" + rememberMe + (returnTo == null ? "" : "&return_to=" + returnTo);
        ResponseEntity<Void> response = http.exchange("/api/v1/auth/oauth/google/authorize" + query, HttpMethod.GET,
                new HttpEntity<>(headers(clientIp, null)), Void.class);
        URI location = response.getHeaders().getLocation();
        String state = location == null ? null : queryOf(location).get("state");
        return new Flow(cookieValue(response, FLOW_COOKIE), state, response);
    }

    protected ResponseEntity<String> callback(String code, String state, String error, String flowCookie,
            String clientIp) {
        StringBuilder path = new StringBuilder("/api/v1/auth/oauth/google/callback?x=1");
        if (code != null) {
            path.append("&code=").append(code);
        }
        if (state != null) {
            path.append("&state=").append(state);
        }
        if (error != null) {
            path.append("&error=").append(error);
        }
        return http.exchange(path.toString(), HttpMethod.GET,
                new HttpEntity<>(headers(clientIp, flowCookie == null ? null : FLOW_COOKIE + "=" + flowCookie)),
                String.class);
    }

    private static HttpHeaders headers(String clientIp, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp);
        if (cookie != null) {
            headers.set(HttpHeaders.COOKIE, cookie);
        }
        return headers;
    }

    protected static Map<String, String> queryOf(URI uri) {
        Map<String, String> query = new HashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            query.put(kv[0], URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        return query;
    }

    protected static String setCookieHeader(ResponseEntity<?> response, String name) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (cookies == null) {
            return null;
        }
        return cookies.stream().filter(c -> c.startsWith(name + "=")).findFirst().orElse(null);
    }

    protected static String cookieValue(ResponseEntity<?> response, String name) {
        String header = setCookieHeader(response, name);
        return header == null ? null : header.substring(name.length() + 1, header.indexOf(';'));
    }

    protected void assertRefusedWith(ResponseEntity<?> response, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(response.getHeaders().getLocation()).hasToString("/login?oauth_error=" + code);
        assertThat(cookieValue(response, REFRESH_COOKIE)).as("no session cookie on a refusal").isNull();
        assertThat(setCookieHeader(response, FLOW_COOKIE)).as("flow cookie cleared").contains("Max-Age=0");
    }

    protected int auditCount(String type, String reason) {
        return jdbc.queryForObject("SELECT count(*) FROM auth_event WHERE type = ?"
                + " AND (?::text IS NULL OR metadata->>'reason' = ?)", Integer.class, type, reason, reason);
    }
}
