package com.flowcortex.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.oauth.AuthorizationRequestCheck.Checked;
import com.flowcortex.user.oauth.AuthorizationRequestCheck.Fault;
import com.flowcortex.user.oauth.AuthorizationRequestCheck.Outcome;
import com.flowcortex.user.oauth.AuthorizationRequestCheck.Params;
import com.flowcortex.user.oauth.AuthorizationRequestCheck.Refused;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuthorizationRequestCheckTest {

    private static final String CALLBACK = "https://claude.ai/api/mcp/auth_callback";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final OAuthClient CLIENT = new OAuthClient("mcp_ab12", "hash", "Claude", List.of(CALLBACK),
            "client_secret_post", Instant.EPOCH);
    private static final OAuthClient LOOPBACK = new OAuthClient("mcp_cd34", null, "CLI",
            List.of("http://127.0.0.1/callback"), "none", Instant.EPOCH);

    private static Params params(String responseType, String redirectUri, String challenge, String method,
            String scope) {
        return new Params(responseType, "mcp_ab12", redirectUri, challenge, method, scope, "xyz", "https://mcp.example");
    }

    private static Params valid() {
        return params("code", CALLBACK, CHALLENGE, "S256", "mcp");
    }

    private static Checked checked(Outcome outcome) {
        assertThat(outcome).isInstanceOf(Checked.class);
        return (Checked) outcome;
    }

    @Test
    void anUnknownClientIsRefusedBeforeAnythingElse() {
        Outcome outcome = AuthorizationRequestCheck.check(params("token", "https://evil.example/cb", null, null, "x"),
                Optional.empty());

        assertThat(outcome).isEqualTo(new Refused(Fault.UNKNOWN_CLIENT));
    }

    @Test
    void anUnregisteredRedirectUriIsRefusedBeforeTheOAuthErrors() {
        assertThat(AuthorizationRequestCheck.check(params("token", "https://evil.example/cb", null, null, "x"),
                Optional.of(CLIENT))).isEqualTo(new Refused(Fault.UNREGISTERED_REDIRECT_URI));
        assertThat(AuthorizationRequestCheck.check(params("code", null, CHALLENGE, "S256", null),
                Optional.of(CLIENT))).isEqualTo(new Refused(Fault.UNREGISTERED_REDIRECT_URI));
        assertThat(AuthorizationRequestCheck.check(
                params("code", "https://claude.ai:8443/api/mcp/auth_callback", CHALLENGE, "S256", null),
                Optional.of(CLIENT))).isEqualTo(new Refused(Fault.UNREGISTERED_REDIRECT_URI));
    }

    @Test
    void aValidRequestIsCheckedWithTheScopeMcpAndNoError() {
        Checked checked = checked(AuthorizationRequestCheck.check(valid(), Optional.of(CLIENT)));

        assertThat(checked.client()).isSameAs(CLIENT);
        assertThat(checked.redirectUri()).isEqualTo(CALLBACK);
        assertThat(checked.scope()).isEqualTo("mcp");
        assertThat(checked.error()).isNull();
    }

    @Test
    void aMissingScopeDefaultsToMcpAndTheResourceIsIgnored() {
        Checked checked = checked(AuthorizationRequestCheck.check(params("code", CALLBACK, CHALLENGE, "S256", null),
                Optional.of(CLIENT)));

        assertThat(checked.scope()).isEqualTo("mcp");
        assertThat(checked.error()).isNull();
    }

    @Test
    void theLoopbackPortMayDifferButTheRequestedUriIsTheOneRedirectedTo() {
        Checked checked = checked(AuthorizationRequestCheck.check(
                params("code", "http://127.0.0.1:53682/callback", CHALLENGE, "S256", "mcp"), Optional.of(LOOPBACK)));

        assertThat(checked.redirectUri()).isEqualTo("http://127.0.0.1:53682/callback");
        assertThat(checked.error()).isNull();
    }

    @Test
    void redirectableFaultsFollowTheOrderResponseTypeChallengeScope() {
        assertThat(error(params("token", CALLBACK, null, "plain", "mcp admin"))).isEqualTo("unsupported_response_type");
        assertThat(error(params("code", CALLBACK, null, "S256", "mcp admin"))).isEqualTo("invalid_request");
        assertThat(error(params("code", CALLBACK, CHALLENGE.substring(0, 42), "S256", "mcp")))
                .isEqualTo("invalid_request");
        assertThat(error(params("code", CALLBACK, CHALLENGE, "plain", "mcp"))).isEqualTo("invalid_request");
        assertThat(error(params("code", CALLBACK, CHALLENGE, null, "mcp"))).isEqualTo("invalid_request");
        assertThat(error(params("code", CALLBACK, CHALLENGE, "S256", "mcp admin"))).isEqualTo("invalid_scope");
        assertThat(error(params("code", CALLBACK, CHALLENGE, "S256", "admin"))).isEqualTo("invalid_scope");
    }

    private static String error(Params params) {
        return checked(AuthorizationRequestCheck.check(params, Optional.of(CLIENT))).error();
    }
}
