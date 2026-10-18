package com.flowcortex.user.application;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Builds the Google authorization URL, exchanges the authorization code (with the PKCE verifier) for
 * tokens and verifies the returned {@code id_token}. Holds no transaction and no state: the caller
 * runs it before any database work, and every failure surfaces as {@link OAuthVerificationException}.
 */
public class GoogleOAuthClient {

    private static final Logger LOG = LoggerFactory.getLogger(GoogleOAuthClient.class);

    private static final String AUTHORIZE_URI = "https://accounts.google.com/o/oauth2/v2/auth";

    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";

    private static final String SCOPES = "openid email profile";

    private final GoogleOAuthProperties properties;

    private final JwtDecoder idTokenDecoder;

    private final RestClient restClient;

    GoogleOAuthClient(GoogleOAuthProperties properties, JwtDecoder idTokenDecoder, RestClient restClient) {
        this.properties = properties;
        this.idTokenDecoder = idTokenDecoder;
        this.restClient = restClient;
    }

    public boolean isConfigured() {
        return properties.clientId() != null && !properties.clientId().isBlank();
    }

    public String buildAuthorizeUrl(OAuthFlowState flow) {
        return AUTHORIZE_URI
                + "?client_id=" + encode(properties.clientId())
                + "&redirect_uri=" + encode(properties.redirectUri())
                + "&response_type=code"
                + "&scope=" + encode(SCOPES)
                + "&state=" + encode(flow.state())
                + "&nonce=" + encode(flow.nonce())
                + "&code_challenge=" + encode(flow.codeChallenge())
                + "&code_challenge_method=S256"
                + "&access_type=online";
    }

    public GoogleIdTokenClaims exchangeAndVerify(String code, String codeVerifier, String expectedNonce) {
        return verify(exchange(code, codeVerifier), expectedNonce);
    }

    private String exchange(String code, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("code_verifier", codeVerifier);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("redirect_uri", properties.redirectUri());
        form.add("grant_type", "authorization_code");

        Map<String, Object> body;
        try {
            body = restClient.post()
                    .uri(URI.create(TOKEN_URI))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
        } catch (RestClientException ex) {
            // Not ex.getMessage(): it can echo the request or Google's response.
            LOG.warn("Google token endpoint call failed: {}", ex.getClass().getSimpleName());
            throw new OAuthVerificationException("token endpoint call failed");
        }
        if (body == null || !(body.get("id_token") instanceof String idToken)) {
            throw new OAuthVerificationException("token endpoint returned no id_token");
        }
        return idToken;
    }

    private GoogleIdTokenClaims verify(String idToken, String expectedNonce) {
        final Jwt jwt;
        try {
            jwt = idTokenDecoder.decode(idToken);
        } catch (JwtException ex) {
            throw new OAuthVerificationException("id_token verification failed");
        }
        if (!expectedNonce.equals(jwt.getClaimAsString("nonce"))) {
            throw new OAuthVerificationException("id_token nonce mismatch");
        }
        String sub = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        if (sub == null || sub.isEmpty() || email == null || email.isEmpty()) {
            throw new OAuthVerificationException("id_token missing sub or email");
        }
        return new GoogleIdTokenClaims(sub, email, true);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
