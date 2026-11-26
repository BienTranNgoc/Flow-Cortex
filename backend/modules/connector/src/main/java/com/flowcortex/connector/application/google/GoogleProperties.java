package com.flowcortex.connector.application.google;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code flowcortex.connectors.google}: the on/off switch (default off), the OAuth client of the connector (separate
 * from Google sign-in), where the callback sends the browser back to, the read timeout and the base URL of each
 * Google endpoint family. Switched on, the client and the redirect URI must be set, otherwise startup fails
 * naming the setting and never its value.
 */
@ConfigurationProperties("flowcortex.connectors.google")
public record GoogleProperties(boolean enabled, String clientId, String clientSecret, String redirectUri,
        String returnPath, Duration readTimeout, URI authBaseUrl, URI oauthBaseUrl, URI apiBaseUrl,
        URI sheetsBaseUrl) {

    private static final String PREFIX = "flowcortex.connectors.google.";

    public GoogleProperties {
        if (enabled) {
            require(clientId, "client-id");
            require(clientSecret, "client-secret");
            require(redirectUri, "redirect-uri");
            if (!isAbsoluteHttp(redirectUri)) {
                throw new IllegalStateException(PREFIX + "redirect-uri must be an absolute http(s) URL");
            }
        }
        returnPath = returnPath == null || returnPath.isBlank() ? "/integrations" : returnPath;
        readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
        authBaseUrl = authBaseUrl == null ? URI.create("https://accounts.google.com/o/oauth2/v2/auth") : authBaseUrl;
        oauthBaseUrl = oauthBaseUrl == null ? URI.create("https://oauth2.googleapis.com") : oauthBaseUrl;
        apiBaseUrl = apiBaseUrl == null ? URI.create("https://www.googleapis.com") : apiBaseUrl;
        sheetsBaseUrl = sheetsBaseUrl == null ? URI.create("https://sheets.googleapis.com") : sheetsBaseUrl;
    }

    private static void require(String value, String setting) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(PREFIX + setting + " must be set when " + PREFIX + "enabled is true");
        }
    }

    private static boolean isAbsoluteHttp(String value) {
        try {
            URI uri = URI.create(value.trim());
            return uri.isAbsolute() && uri.getHost() != null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "GoogleProperties[enabled=" + enabled + "]";
    }
}
