package com.flowcortex.user.oauth;

import com.flowcortex.user.application.RateLimit;
import java.time.Duration;

/**
 * The switch and limits of the MCP authorization server ({@code flowcortex.auth.mcp-oauth.*}) together
 * with the two other settings it cannot run without. Turning it on without them stops startup.
 */
record McpOAuthProperties(boolean enabled, boolean personalAccessTokensEnabled, String publicUrl,
        RateLimit registerPerIp, RateLimit tokenPerIp, Duration retentionInterval) {

    McpOAuthProperties {
        if (enabled && !personalAccessTokensEnabled) {
            throw new IllegalStateException("flowcortex.auth.mcp-oauth.enabled needs"
                    + " flowcortex.auth.personal-access-tokens.enabled (FLOWCORTEX_AUTH_PERSONAL_ACCESS_TOKENS_ENABLED)"
                    + " to be true: the access token this server issues is a personal access token");
        }
        if (enabled && (publicUrl == null || publicUrl.isBlank())) {
            throw new IllegalStateException("flowcortex.auth.mcp-oauth.enabled needs flowcortex.app.public-url"
                    + " (FLOWCORTEX_APP_PUBLIC_URL), the member app origin published as the issuer");
        }
        requireLimit("register-per-ip", registerPerIp);
        requireLimit("token-per-ip", tokenPerIp);
        if (retentionInterval == null || retentionInterval.isZero() || retentionInterval.isNegative()) {
            throw new IllegalStateException(
                    "flowcortex.auth.mcp-oauth.retention-interval must be positive, was " + retentionInterval);
        }
    }

    /** The public URL without a trailing slash, the {@code issuer} of the metadata. */
    String issuer() {
        String url = publicUrl == null ? "" : publicUrl.strip();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static void requireLimit(String name, RateLimit limit) {
        String setting = "flowcortex.auth.mcp-oauth." + name;
        if (limit == null || limit.capacity() <= 0 || limit.period() == null || limit.period().isZero()
                || limit.period().isNegative()) {
            throw new IllegalStateException(setting + " needs a positive capacity and period, was " + limit);
        }
    }
}
