package com.flowcortex.user.application;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Google OAuth 2.0 settings. {@code clientId} / {@code clientSecret} may be
 * empty in dev — {@link GoogleOAuthClient} throws on use, so the rest of the
 * app still boots.
 */
@ConfigurationProperties("flowcortex.security.oauth.google")
public record GoogleOAuthProperties(String clientId, String clientSecret, List<String> redirectUris, Duration stateTtl,
        boolean requireExplicitLink) {

    public GoogleOAuthProperties {
        if (stateTtl == null) {
            stateTtl = Duration.ofMinutes(10);
        }
        if (redirectUris == null) {
            redirectUris = List.of();
        }
    }
}
