package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GoogleOAuthClientTest {

    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String CLIENT_ID = "client-1.apps.googleusercontent.com";
    private static final String REDIRECT_URI = "https://app.example/api/v1/auth/oauth/google/callback";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC);

    private final GoogleOAuthProperties properties = new GoogleOAuthProperties(CLIENT_ID, "secret", REDIRECT_URI,
            Duration.ofMinutes(10), Duration.ofSeconds(5), Duration.ofSeconds(10));
    private final JwtDecoder decoder = mock(JwtDecoder.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final GoogleOAuthClient client = new GoogleOAuthClient(properties, decoder, builder.build());

    private static Jwt idToken(String nonce, String email) {
        return Jwt.withTokenValue("id-token").header("alg", "RS256").subject("sub-1").claim("email", email)
                .claim("nonce", nonce).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    private void tokenEndpointAnswers(String body) {
        server.expect(requestTo(TOKEN_URI)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void theCodeIsExchangedWithTheVerifierAndTheVerifiedClaimsAreReturned() {
        server.expect(requestTo(TOKEN_URI)).andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of("code", "the-code", "code_verifier", "the-verifier",
                        "client_id", CLIENT_ID, "client_secret", "secret", "redirect_uri", REDIRECT_URI,
                        "grant_type", "authorization_code")))
                .andRespond(withSuccess("{\"id_token\":\"raw\"}", MediaType.APPLICATION_JSON));
        when(decoder.decode("raw")).thenReturn(idToken("n-1", "a@example.com"));

        GoogleIdTokenClaims claims = client.exchangeAndVerify("the-code", "the-verifier", "n-1");

        assertThat(claims).isEqualTo(new GoogleIdTokenClaims("sub-1", "a@example.com", true));
        server.verify();
    }

    @Test
    void theDisplayNameIsTheTrimmedNameElseTheGivenAndFamilyNamesElseNothing() {
        assertThat(GoogleOAuthClient.displayName(withNames(Map.of("name", "  Nguyen Van A ",
                "given_name", "A")))).isEqualTo("Nguyen Van A");
        assertThat(GoogleOAuthClient.displayName(withNames(Map.of("name", "   ", "given_name", " Binh ",
                "family_name", "Le")))).isEqualTo("Binh Le");
        assertThat(GoogleOAuthClient.displayName(withNames(Map.of("family_name", "Le")))).isEqualTo("Le");
        assertThat(GoogleOAuthClient.displayName(withNames(Map.of("name", List.of("not", "text"))))).isNull();
        assertThat(GoogleOAuthClient.displayName(withNames(Map.of()))).isNull();
    }

    @Test
    void aLongDisplayNameIsCutToOneHundredCharactersWithoutSplittingOne() {
        String emoji = "😀";

        String cut = GoogleOAuthClient.displayName(withNames(Map.of("name", emoji.repeat(150))));

        assertThat(cut).isEqualTo(emoji.repeat(100));
        assertThat(GoogleOAuthClient.displayName(withNames(Map.of("given_name", "g".repeat(80),
                "family_name", "f".repeat(80))))).hasSize(100);
    }

    private static Jwt withNames(Map<String, Object> names) {
        Jwt.Builder token = Jwt.withTokenValue("id-token").header("alg", "RS256").subject("sub-1")
                .claim("email", "a@example.com");
        names.forEach(token::claim);
        return token.build();
    }

    @Test
    void aTokenWithAnotherNonceIsRefused() {
        tokenEndpointAnswers("{\"id_token\":\"raw\"}");
        when(decoder.decode("raw")).thenReturn(idToken("someone-elses-nonce", "a@example.com"));

        assertThatThrownBy(() -> client.exchangeAndVerify("c", "v", "n-1"))
                .isInstanceOf(OAuthVerificationException.class);
    }

    @Test
    void aTokenWithoutANonceIsRefused() {
        tokenEndpointAnswers("{\"id_token\":\"raw\"}");
        when(decoder.decode("raw")).thenReturn(idToken(null, "a@example.com"));

        assertThatThrownBy(() -> client.exchangeAndVerify("c", "v", "n-1"))
                .isInstanceOf(OAuthVerificationException.class);
    }

    @Test
    void aTokenTheDecoderRejectsIsRefused() {
        tokenEndpointAnswers("{\"id_token\":\"raw\"}");
        when(decoder.decode(any())).thenThrow(new JwtException("bad signature"));

        assertThatThrownBy(() -> client.exchangeAndVerify("c", "v", "n-1"))
                .isInstanceOf(OAuthVerificationException.class);
    }

    @Test
    void aTokenMissingItsSubjectOrEmailIsRefused() {
        tokenEndpointAnswers("{\"id_token\":\"raw\"}");
        when(decoder.decode("raw")).thenReturn(idToken("n-1", null));

        assertThatThrownBy(() -> client.exchangeAndVerify("c", "v", "n-1"))
                .isInstanceOf(OAuthVerificationException.class);
    }

    @Test
    void aResponseWithoutAnIdTokenIsRefused() {
        tokenEndpointAnswers("{\"access_token\":\"x\"}");

        assertThatThrownBy(() -> client.exchangeAndVerify("c", "v", "n-1"))
                .isInstanceOf(OAuthVerificationException.class);
    }

    @Test
    void aGoogleErrorResponseIsRefusedWithoutLeakingTheCode() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"invalid_grant\"}"));

        assertThatThrownBy(() -> client.exchangeAndVerify("secret-code-123", "v", "n-1"))
                .isInstanceOf(OAuthVerificationException.class)
                .hasMessageNotContaining("secret-code-123");
    }

    @Test
    void aGoogleServerErrorIsRefused() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withServerError());

        assertThatThrownBy(() -> client.exchangeAndVerify("c", "v", "n-1"))
                .isInstanceOf(OAuthVerificationException.class);
    }

    @Test
    void aTimeoutOrConnectionFailureIsRefused() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withException(new IOException("Read timed out")));

        assertThatThrownBy(() -> client.exchangeAndVerify("c", "v", "n-1"))
                .isInstanceOf(OAuthVerificationException.class);
    }

    @Test
    void theAuthorizeUrlCarriesTheS256ChallengeTheNonceTheStateAndTheConfiguredRedirectUri() {
        OAuthFlowState flow = OAuthFlowState.begin("/", false, Duration.ofMinutes(10), CLOCK);

        URI url = URI.create(client.buildAuthorizeUrl(flow));

        Map<String, String> query = new java.util.HashMap<>();
        for (String pair : url.getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            query.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        assertThat(url.getHost()).isEqualTo("accounts.google.com");
        assertThat(query).containsEntry("client_id", CLIENT_ID).containsEntry("redirect_uri", REDIRECT_URI)
                .containsEntry("response_type", "code").containsEntry("state", flow.state())
                .containsEntry("nonce", flow.nonce()).containsEntry("code_challenge", flow.codeChallenge())
                .containsEntry("code_challenge_method", "S256");
        assertThat(List.of(query.get("scope").split(" "))).contains("openid", "email");
    }

    @Test
    void aBlankClientIdMeansNotConfigured() {
        GoogleOAuthProperties blank = new GoogleOAuthProperties("", "", REDIRECT_URI, null, null, null);

        assertThat(new GoogleOAuthClient(blank, decoder, builder.build()).isConfigured()).isFalse();
        assertThat(client.isConfigured()).isTrue();
        assertThat(blank.stateTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(blank.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(blank.readTimeout()).isEqualTo(Duration.ofSeconds(10));
    }
}
