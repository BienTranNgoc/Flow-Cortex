package com.flowcortex.user.apicall;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.shared.security.PersonalAccessTokenAuthenticator;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class ApiTokenCallRouteTest {

    private static Jwt jwt(String authClaim) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").subject(UUID.randomUUID().toString());
        if (authClaim != null) {
            builder.claim(PersonalAccessTokenAuthenticator.AUTH_CLAIM, authClaim);
        }
        return builder.build();
    }

    @Test
    void theMatchedPatternIsTheRouteWhenPresent() {
        assertThat(ApiTokenCallRoute.of("/api/v1/workflows/{id}/steps", "/api/v1/workflows/42/steps"))
                .isEqualTo("/api/v1/workflows/{id}/steps");
    }

    @Test
    void withoutAPatternUuidAndNumericSegmentsBecomeAnId() {
        assertThat(ApiTokenCallRoute.of(null, "/api/v1/workflows/7c1e0b9a-1f2e-4c3d-9a8b-0123456789ab/steps/12"))
                .isEqualTo("/api/v1/workflows/{id}/steps/{id}");
    }

    @Test
    void aSegmentThatOnlyStartsWithDigitsStaysAsIs() {
        assertThat(ApiTokenCallRoute.normalize("/api/v1/2fa/12ab")).isEqualTo("/api/v1/2fa/12ab");
    }

    @Test
    void theQueryIsDropped() {
        assertThat(ApiTokenCallRoute.normalize("/api/v1/workflows?secret=abc")).isEqualTo("/api/v1/workflows");
    }

    @Test
    void aBlankPatternFallsBackToThePath() {
        assertThat(ApiTokenCallRoute.of(" ", "/api/v1/runs/9")).isEqualTo("/api/v1/runs/{id}");
    }

    @Test
    void aLongRouteIsCutToTwoHundredCharacters() {
        String path = "/" + "a".repeat(500);

        assertThat(ApiTokenCallRoute.normalize(path)).hasSize(200);
        assertThat(ApiTokenCallRoute.of("/" + "b".repeat(500), path)).hasSize(200);
    }

    @Test
    void onlyAWriteWithATokenPrincipalActs() {
        Jwt token = jwt(PersonalAccessTokenAuthenticator.AUTH_PAT);

        assertThat(ApiTokenCallRoute.acts("POST", token)).isTrue();
        assertThat(ApiTokenCallRoute.acts("PUT", token)).isTrue();
        assertThat(ApiTokenCallRoute.acts("PATCH", token)).isTrue();
        assertThat(ApiTokenCallRoute.acts("DELETE", token)).isTrue();
        assertThat(ApiTokenCallRoute.acts("GET", token)).isFalse();
        assertThat(ApiTokenCallRoute.acts("HEAD", token)).isFalse();
        assertThat(ApiTokenCallRoute.acts("OPTIONS", token)).isFalse();
    }

    @Test
    void aSessionOrAnonymousPrincipalNeverActs() {
        assertThat(ApiTokenCallRoute.acts("POST", jwt(null))).isFalse();
        assertThat(ApiTokenCallRoute.acts("POST", jwt("session"))).isFalse();
        assertThat(ApiTokenCallRoute.acts("POST", null)).isFalse();
        assertThat(ApiTokenCallRoute.acts("POST", "anonymousUser")).isFalse();
    }

    @Test
    void aWorkflowCreateIsAPostOnTheExactWorkflowsPath() {
        assertThat(ApiTokenCallRoute.isWorkflowCreate("POST", "/api/v1/workflows")).isTrue();
        assertThat(ApiTokenCallRoute.isWorkflowCreate("PATCH", "/api/v1/workflows")).isFalse();
        assertThat(ApiTokenCallRoute.isWorkflowCreate("POST", "/api/v1/workflows/")).isFalse();
        assertThat(ApiTokenCallRoute.isWorkflowCreate("POST", "/api/v1/workflows/42/steps")).isFalse();
    }
}
