package com.flowcortex.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.application.OpaqueTokens;
import com.flowcortex.user.oauth.ClientCredentials.Credentials;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientCredentialsTest {

    private static final String SECRET = "s3cr3t-value";
    private static final OAuthClient CONFIDENTIAL = new OAuthClient("mcp_ab12", OpaqueTokens.hash(SECRET), "Claude",
            List.of("https://claude.ai/api/mcp/auth_callback"), "client_secret_post", Instant.EPOCH);
    private static final OAuthClient PUBLIC = new OAuthClient("mcp_cd34", null, "CLI",
            List.of("http://127.0.0.1/cb"), "none", Instant.EPOCH);

    private static String basic(String raw) {
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void basicBeatsTheFormFields() {
        Credentials credentials = ClientCredentials.from(basic("mcp_ab12:" + SECRET), "mcp_other", "wrong").orElseThrow();

        assertThat(credentials).isEqualTo(new Credentials("mcp_ab12", SECRET, true));
    }

    @Test
    void theBasicIdAndSecretArePercentDecoded() {
        Credentials credentials = ClientCredentials.from(basic("mcp%5Fab12:a%3Ab%2Bc+d"), null, null).orElseThrow();

        assertThat(credentials.clientId()).isEqualTo("mcp_ab12");
        assertThat(credentials.secret()).isEqualTo("a:b+c d");
    }

    @Test
    void aMalformedBasicHeaderYieldsNothingEvenWithFormFields() {
        assertThat(ClientCredentials.from("Basic !!!not-base64!!!", "mcp_ab12", SECRET)).isEmpty();
        assertThat(ClientCredentials.from(basic("no-colon"), "mcp_ab12", SECRET)).isEmpty();
        assertThat(ClientCredentials.from(basic(":only-secret"), "mcp_ab12", SECRET)).isEmpty();
        assertThat(ClientCredentials.from(basic("mcp_ab12:%zz"), null, null)).isEmpty();
    }

    @Test
    void formFieldsAreUsedWithoutAHeaderAndAnIdAloneIsACredentialWithoutASecret() {
        assertThat(ClientCredentials.from(null, "mcp_ab12", SECRET).orElseThrow())
                .isEqualTo(new Credentials("mcp_ab12", SECRET, false));
        assertThat(ClientCredentials.from("Bearer xyz", "mcp_cd34", null).orElseThrow())
                .isEqualTo(new Credentials("mcp_cd34", null, false));
        assertThat(ClientCredentials.from(null, "mcp_cd34", "").orElseThrow().secret()).isNull();
        assertThat(ClientCredentials.from(null, null, SECRET)).isEmpty();
        assertThat(ClientCredentials.from(null, " ", SECRET)).isEmpty();
    }

    @Test
    void aConfidentialClientNeedsItsExactSecret() {
        assertThat(ClientCredentials.verify(CONFIDENTIAL, new Credentials("mcp_ab12", SECRET, false))).isTrue();
        assertThat(ClientCredentials.verify(CONFIDENTIAL, new Credentials("mcp_ab12", "wrong", false))).isFalse();
        assertThat(ClientCredentials.verify(CONFIDENTIAL, new Credentials("mcp_ab12", null, false))).isFalse();
        assertThat(ClientCredentials.verify(CONFIDENTIAL, new Credentials("mcp_other", SECRET, false))).isFalse();
    }

    @Test
    void aPublicClientSendsNoSecretAndAnySecretIsRefused() {
        assertThat(ClientCredentials.verify(PUBLIC, new Credentials("mcp_cd34", null, false))).isTrue();
        assertThat(ClientCredentials.verify(PUBLIC, new Credentials("mcp_cd34", "anything", false))).isFalse();
    }
}
