package com.flowcortex.user.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Google OAuth 2.0 settings. An empty {@code clientId} leaves the app booting with Google sign-in
 * switched off ({@link GoogleOAuthClient#isConfigured()}). {@code redirectUri} is the absolute
 * backend callback URL registered at Google.
 */
@ConfigurationProperties("flowcortex.security.oauth.google")
public record GoogleOAuthProperties(String clientId, String clientSecret, String redirectUri, Duration stateTtl,
        Duration connectTimeout, Duration readTimeout) {

    public GoogleOAuthProperties {
        if (stateTtl == null) {
            stateTtl = Duration.ofMinutes(10);
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(5);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(10);
        }
    }
}
