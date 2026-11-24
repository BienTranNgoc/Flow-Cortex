package com.flowcortex.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.user.application.RateLimit;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class McpOAuthPropertiesTest {

    private static final RateLimit REGISTER = new RateLimit(10, Duration.ofHours(1));
    private static final RateLimit TOKEN = new RateLimit(30, Duration.ofMinutes(1));

    private static McpOAuthProperties properties(boolean enabled, boolean patEnabled, String publicUrl,
            RateLimit register, RateLimit token, Duration retention) {
        return new McpOAuthProperties(enabled, patEnabled, publicUrl, register, token, retention);
    }

    @Test
    void enabledWithPersonalAccessTokensOffFailsNamingThatSetting() {
        assertThatThrownBy(() -> properties(true, false, "https://app.nodi.vn", REGISTER, TOKEN, Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.auth.personal-access-tokens.enabled");
    }

    @Test
    void enabledWithABlankPublicUrlFailsNamingThatSetting() {
        assertThatThrownBy(() -> properties(true, true, " ", REGISTER, TOKEN, Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("flowcortex.app.public-url");
        assertThatThrownBy(() -> properties(true, true, null, REGISTER, TOKEN, Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("flowcortex.app.public-url");
    }

    @Test
    void disabledNeedsNeitherTheTokensNorTheUrl() {
        assertThatCode(() -> properties(false, false, "", REGISTER, TOKEN, Duration.ofHours(1)))
                .doesNotThrowAnyException();
    }

    @Test
    void theIssuerIsThePublicUrlWithoutItsTrailingSlash() {
        assertThat(properties(true, true, "https://app.nodi.vn/", REGISTER, TOKEN, Duration.ofHours(1)).issuer())
                .isEqualTo("https://app.nodi.vn");
        assertThat(properties(true, true, " https://app.nodi.vn ", REGISTER, TOKEN, Duration.ofHours(1)).issuer())
                .isEqualTo("https://app.nodi.vn");
    }

    @Test
    void aNonPositiveLimiterCapacityOrRetentionIntervalFailsNamingTheSetting() {
        assertThatThrownBy(() -> properties(false, false, "", new RateLimit(0, Duration.ofHours(1)), TOKEN,
                Duration.ofHours(1))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.auth.mcp-oauth.register-per-ip");
        assertThatThrownBy(() -> properties(false, false, "", REGISTER, new RateLimit(-1, Duration.ofMinutes(1)),
                Duration.ofHours(1))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.auth.mcp-oauth.token-per-ip");
        assertThatThrownBy(() -> properties(false, false, "", REGISTER, TOKEN, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.auth.mcp-oauth.retention-interval");
        assertThatThrownBy(() -> properties(false, false, "", REGISTER, TOKEN, Duration.ofHours(-1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.auth.mcp-oauth.retention-interval");
    }
}
