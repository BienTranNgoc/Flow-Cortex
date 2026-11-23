package com.flowcortex.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.oauth.ClientRegistrationRules.Invalid;
import com.flowcortex.user.oauth.ClientRegistrationRules.Outcome;
import com.flowcortex.user.oauth.ClientRegistrationRules.Request;
import com.flowcortex.user.oauth.ClientRegistrationRules.Valid;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientRegistrationRulesTest {

    private static final List<String> URIS = List.of("https://claude.ai/api/mcp/auth_callback");

    private static Request request(String name, List<String> grants, List<String> responses, String method) {
        return new Request(name, URIS, grants, responses, method);
    }

    private static Valid valid(Outcome outcome) {
        assertThat(outcome).isInstanceOf(Valid.class);
        return (Valid) outcome;
    }

    private static void assertInvalid(Outcome outcome, String error) {
        assertThat(outcome).isInstanceOfSatisfying(Invalid.class, invalid -> {
            assertThat(invalid.error()).isEqualTo(error);
            assertThat(invalid.description()).isNotBlank();
        });
    }

    @Test
    void anEmptyRequestGetsTheDefaults() {
        Valid valid = valid(ClientRegistrationRules.evaluate(request(null, null, null, null)));

        assertThat(valid.authMethod()).isEqualTo("client_secret_post");
        assertThat(valid.clientName()).isEqualTo("MCP client");
        assertThat(valid.redirectUris()).isEqualTo(URIS);
    }

    @Test
    void controlCharactersAreStrippedFromTheName() {
        assertThat(valid(ClientRegistrationRules.evaluate(request("Claude\u0000\nDesktop", null, null, null)))
                .clientName()).isEqualTo("ClaudeDesktop");
        assertThat(valid(ClientRegistrationRules.evaluate(request("\u0000\n ", null, null, null))).clientName())
                .isEqualTo("MCP client");
    }

    @Test
    void aNameOf128CharactersPassesAndOf129IsRefused() {
        assertThat(valid(ClientRegistrationRules.evaluate(request("n".repeat(128), null, null, null))).clientName())
                .hasSize(128);
        assertInvalid(ClientRegistrationRules.evaluate(request("n".repeat(129), null, null, null)),
                "invalid_client_metadata");
    }

    @Test
    void unsupportedMetadataIsInvalidClientMetadata() {
        assertInvalid(ClientRegistrationRules.evaluate(request(null, List.of("client_credentials"), null, null)),
                "invalid_client_metadata");
        assertInvalid(ClientRegistrationRules.evaluate(request(null, List.of("refresh_token"), null, null)),
                "invalid_client_metadata");
        assertInvalid(ClientRegistrationRules.evaluate(
                request(null, List.of("authorization_code", "client_credentials"), null, null)),
                "invalid_client_metadata");
        assertInvalid(ClientRegistrationRules.evaluate(request(null, null, List.of("token"), null)),
                "invalid_client_metadata");
        assertInvalid(ClientRegistrationRules.evaluate(request(null, null, List.of(), null)),
                "invalid_client_metadata");
        assertInvalid(ClientRegistrationRules.evaluate(request(null, null, null, "private_key_jwt")),
                "invalid_client_metadata");
    }

    @Test
    void mcpClientsMayAskForRefreshTokenAlongsideTheCodeGrant() {
        assertThat(ClientRegistrationRules.evaluate(
                request(null, List.of("authorization_code", "refresh_token"), List.of("code"), "none")))
                .isInstanceOf(Valid.class);
    }

    @Test
    void aBadRedirectUriIsInvalidRedirectUriBeforeAnyMetadataCheck() {
        Request bad = new Request("n".repeat(129), List.of("http://evil.example/cb"), List.of("token"), null, "x");

        assertInvalid(ClientRegistrationRules.evaluate(bad), "invalid_redirect_uri");
    }
}
