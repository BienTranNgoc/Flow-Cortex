package com.flowcortex.connector.application.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.connector.application.google.GoogleOAuthRules.GrantFault;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoogleOAuthRulesTest {

    private static final String DRIVE = "https://www.googleapis.com/auth/drive.readonly";
    private static final String SHEETS = "https://www.googleapis.com/auth/spreadsheets";
    private static final String EMAIL = "https://www.googleapis.com/auth/userinfo.email";
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private static GoogleProperties enabledProperties() {
        return new GoogleProperties(true, "cid", "csecret", "https://app.example/api/v1/connectors/google/callback",
                null, null, null, null, null, null);
    }

    @Test
    void renewalIsNeededWhenTheTokenExpiresInLessThanTwoMinutes() {
        assertThat(GoogleOAuthRules.needsRenewal(NOW.plusSeconds(119), NOW)).isTrue();
        assertThat(GoogleOAuthRules.needsRenewal(NOW, NOW)).isTrue();
        assertThat(GoogleOAuthRules.needsRenewal(NOW.minusSeconds(30), NOW)).isTrue();
        assertThat(GoogleOAuthRules.needsRenewal(NOW.plusSeconds(121), NOW)).isFalse();
        assertThat(GoogleOAuthRules.needsRenewal(NOW.plus(Duration.ofMinutes(30)), NOW)).isFalse();
    }

    @Test
    void aMissingExpiryNeverTriggersARenewal() {
        assertThat(GoogleOAuthRules.needsRenewal(null, NOW)).isFalse();
    }

    @Test
    void aGrantMissingAnyOneScopeIsRefused() {
        assertThat(GoogleOAuthRules.grantFault(List.of(SHEETS, EMAIL), "rt")).isEqualTo(GrantFault.SCOPE_MISSING);
        assertThat(GoogleOAuthRules.grantFault(List.of(DRIVE, EMAIL), "rt")).isEqualTo(GrantFault.SCOPE_MISSING);
        assertThat(GoogleOAuthRules.grantFault(List.of(DRIVE, SHEETS), "rt")).isEqualTo(GrantFault.SCOPE_MISSING);
        assertThat(GoogleOAuthRules.grantFault(null, "rt")).isEqualTo(GrantFault.SCOPE_MISSING);
    }

    @Test
    void aGrantWithoutARefreshTokenIsRefused() {
        assertThat(GoogleOAuthRules.grantFault(List.of(DRIVE, SHEETS, EMAIL), null)).isEqualTo(GrantFault.NO_REFRESH_TOKEN);
        assertThat(GoogleOAuthRules.grantFault(List.of(DRIVE, SHEETS, EMAIL), "  ")).isEqualTo(GrantFault.NO_REFRESH_TOKEN);
    }

    @Test
    void aCompleteGrantHasNoFaultWhateverTheScopeOrderOrExtras() {
        assertThat(GoogleOAuthRules.grantFault(List.of(EMAIL, DRIVE, "openid", SHEETS), "rt")).isNull();
    }

    @Test
    void missingScopesListsTheAbsentOnesInTheConstantsOrder() {
        assertThat(GoogleOAuthRules.missingScopes(List.of(EMAIL))).containsExactly(DRIVE, SHEETS);
        assertThat(GoogleOAuthRules.missingScopes(List.of(SHEETS, DRIVE, EMAIL))).isEmpty();
        assertThat(GoogleOAuthRules.missingScopes(null)).containsExactly(DRIVE, SHEETS, EMAIL);
    }

    @Test
    void theAuthorizationUrlAsksForOfflineAccessWithPkceAndTheThreeScopes() {
        String url = GoogleOAuthRules.authorizationUrl(enabledProperties(), "st4te", "chall3nge");

        assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth?").doesNotContain(" ");
        Map<String, String> query = query(url);
        assertThat(query).containsEntry("response_type", "code")
                .containsEntry("access_type", "offline")
                .containsEntry("prompt", "consent")
                .containsEntry("include_granted_scopes", "true")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("code_challenge", "chall3nge")
                .containsEntry("state", "st4te")
                .containsEntry("client_id", "cid")
                .containsEntry("redirect_uri", "https://app.example/api/v1/connectors/google/callback")
                .containsEntry("scope", DRIVE + " " + SHEETS + " " + EMAIL);
        assertThat(url).doesNotContain("csecret").doesNotContain("redirect_uri=https://");
    }

    @Test
    void switchedOnWithoutAClientIdStartupFailsNamingTheSetting() {
        assertThatThrownBy(() -> new GoogleProperties(true, "", "csecret", "https://app.example/cb", null, null, null,
                null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.connectors.google.client-id")
                .hasMessageNotContaining("csecret");
    }

    @Test
    void switchedOnWithoutASecretOrWithARelativeRedirectUriNamesTheSettingAndNeverTheValue() {
        assertThatThrownBy(() -> new GoogleProperties(true, "cid", " ", "https://app.example/cb", null, null, null,
                null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.connectors.google.client-secret");
        assertThatThrownBy(() -> new GoogleProperties(true, "cid", "csecret", "/api/v1/connectors/google/callback",
                null, null, null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.connectors.google.redirect-uri")
                .hasMessageNotContaining("csecret");
        assertThatThrownBy(() -> new GoogleProperties(true, "cid", "csecret", "ftp://app.example/cb", null, null,
                null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.connectors.google.redirect-uri");
    }

    @Test
    void switchedOffNeedsNoClientAndTheSecretNeverShowsInToString() {
        GoogleProperties off = new GoogleProperties(false, null, null, null, null, null, null, null, null, null);

        assertThat(off.enabled()).isFalse();
        assertThat(off.returnPath()).isEqualTo("/integrations");
        assertThat(off.readTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(enabledProperties().toString()).doesNotContain("csecret");
    }

    private static Map<String, String> query(String url) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            int eq = pair.indexOf('=');
            values.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return values;
    }
}
