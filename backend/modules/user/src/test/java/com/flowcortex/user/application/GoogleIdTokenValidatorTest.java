package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class GoogleIdTokenValidatorTest {

    private static final String CLIENT_ID = "client-1.apps.googleusercontent.com";

    private final GoogleIdTokenValidator validator = new GoogleIdTokenValidator(CLIENT_ID);

    private static Jwt jwt(Map<String, Object> overrides) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", "https://accounts.google.com");
        claims.put("aud", List.of(CLIENT_ID));
        claims.put("sub", "1234");
        claims.put("email", "a@example.com");
        claims.put("email_verified", true);
        claims.putAll(overrides);
        claims.values().removeIf(v -> v == null);
        return Jwt.withTokenValue("t").header("alg", "RS256").claims(c -> c.putAll(claims))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    private boolean accepts(Map<String, Object> overrides) {
        return !validator.validate(jwt(overrides)).hasErrors();
    }

    @Test
    void aWellFormedTokenIsAccepted() {
        assertThat(accepts(Map.of())).isTrue();
    }

    @Test
    void bothGoogleIssuerSpellingsAreAccepted() {
        assertThat(accepts(Map.of("iss", "accounts.google.com"))).isTrue();
    }

    @Test
    void anotherIssuerIsRejected() {
        assertThat(accepts(Map.of("iss", "https://evil.example"))).isFalse();
    }

    @Test
    void anAudienceWithoutOurClientIdIsRejected() {
        assertThat(accepts(Map.of("aud", List.of("someone-else")))).isFalse();
    }

    @Test
    void ourClientIdAnywhereInTheAudienceIsEnoughWhenAzpIsOurs() {
        assertThat(accepts(Map.of("aud", List.of("other", CLIENT_ID), "azp", CLIENT_ID))).isTrue();
    }

    @Test
    void aMultiAudienceTokenWithoutAzpIsRejected() {
        assertThat(accepts(Map.of("aud", List.of("other", CLIENT_ID)))).isFalse();
    }

    @Test
    void anAzpThatIsNotOurClientIsRejected() {
        assertThat(accepts(Map.of("azp", "someone-else"))).isFalse();
    }

    @Test
    void anUnverifiedOrMissingEmailVerifiedIsRejected() {
        assertThat(accepts(Map.of("email_verified", false))).isFalse();
        Map<String, Object> none = new HashMap<>();
        none.put("email_verified", null);
        assertThat(accepts(none)).isFalse();
    }
}
