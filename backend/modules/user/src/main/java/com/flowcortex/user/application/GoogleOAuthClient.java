package com.flowcortex.user.application;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Exchanges an authorization code for tokens and verifies the returned
 * {@code id_token} against Google's JWKS. The client uses
 * {@link RestClient} (Spring Framework 6.1+) — no new dependency. In
 * integration tests the bean is replaced with one pointing at WireMock.
 */
@Component
public class GoogleOAuthClient {

    private static final Logger LOG = LoggerFactory.getLogger(GoogleOAuthClient.class);

    private static final String GOOGLE_TOKEN_URI = "https://oauth2.googleapis.com/token";

    private static final String ISSUER = "https://accounts.google.com";

    private final GoogleOAuthProperties properties;

    private final JwtDecoder jwtDecoder;

    private final RestClient restClient;

    public GoogleOAuthClient(GoogleOAuthProperties properties, JwtDecoder googleIdTokenDecoder) {
        this.properties = properties;
        this.jwtDecoder = googleIdTokenDecoder;
        this.restClient = RestClient.create();
    }

    public GoogleIdTokenClaims exchangeAndVerify(String code, String redirectUri) {
        if (properties.clientId() == null || properties.clientId().isEmpty()) {
            throw new IllegalStateException("flowcortex.security.oauth.google.client-id is not configured");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");

        ResponseEntity<Map> response = restClient.post()
                .uri(URI.create(GOOGLE_TOKEN_URI))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .body(form)
                .retrieve()
                .toEntity(Map.class);

        if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
            throw new OAuthVerificationException("token endpoint returned " + response.getStatusCode());
        }
        Object idTokenObj = response.getBody().get("id_token");
        if (!(idTokenObj instanceof String idToken)) {
            throw new OAuthVerificationException("token endpoint returned no id_token");
        }
        return verify(idToken);
    }

    private GoogleIdTokenClaims verify(String idToken) {
        final Jwt jwt;
        try {
            jwt = jwtDecoder.decode(idToken);
        } catch (JwtException ex) {
            throw new OAuthVerificationException("id_token signature verification failed");
        }
        if (!ISSUER.equals(jwt.getIssuer())) {
            throw new OAuthVerificationException("id_token issuer mismatch");
        }
        Object aud = jwt.getAudience() == null || jwt.getAudience().isEmpty() ? null : jwt.getAudience().get(0);
        if (aud == null || !aud.equals(properties.clientId())) {
            throw new OAuthVerificationException("id_token audience mismatch");
        }
        Instant now = Instant.now();
        if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)) {
            throw new OAuthVerificationException("id_token expired");
        }
        Boolean emailVerified = jwt.getClaim("email_verified");
        if (emailVerified == null || !emailVerified) {
            throw new OAuthVerificationException("id_token email_verified is false");
        }
        String sub = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        if (sub == null || sub.isEmpty() || email == null || email.isEmpty()) {
            throw new OAuthVerificationException("id_token missing sub or email");
        }
        LOG.debug("Verified Google id_token for sub={}", sub);
        return new GoogleIdTokenClaims(sub, email, true);
    }

    /** Helper for the controller: build the Google authorization URL. */
    public String buildAuthorizeUrl(String state, String redirectUri, List<String> scopes) {
        StringBuilder sb = new StringBuilder("https://accounts.google.com/o/oauth2/v2/auth");
        sb.append("?client_id=").append(encode(properties.clientId()));
        sb.append("&redirect_uri=").append(encode(redirectUri));
        sb.append("&response_type=code");
        sb.append("&scope=").append(encode(String.join(" ", scopes)));
        sb.append("&access_type=online");
        sb.append("&state=").append(encode(state));
        return sb.toString();
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Avoids an unused-import warning. */
    @SuppressWarnings("unused")
    private static void unused(RequestEntity<?> ignored) {
    }
}
