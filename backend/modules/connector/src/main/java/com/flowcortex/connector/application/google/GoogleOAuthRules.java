package com.flowcortex.connector.application.google;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The pure rules of the Google OAuth grant: scopes, renewal timing, grant checks and the consent URL. */
public final class GoogleOAuthRules {

    public static final String SCOPE_DRIVE = "https://www.googleapis.com/auth/drive.readonly";
    public static final String SCOPE_SHEETS = "https://www.googleapis.com/auth/spreadsheets";
    public static final String SCOPE_EMAIL = "https://www.googleapis.com/auth/userinfo.email";

    /** Every scope the connector's code depends on, in the order the consent URL lists them. */
    public static final List<String> SCOPES = List.of(SCOPE_DRIVE, SCOPE_SHEETS, SCOPE_EMAIL);

    /** An access token this close to its expiry is renewed before use. */
    public static final Duration RENEW_BEFORE = Duration.ofSeconds(120);

    /** How long a started connect may take to come back through the callback. */
    public static final Duration FLOW_TTL = Duration.ofMinutes(10);

    /** Why a grant Google returned is not stored. */
    public enum GrantFault { SCOPE_MISSING, NO_REFRESH_TOKEN }

    private GoogleOAuthRules() {
    }

    /** Whether the token expires in less than {@link #RENEW_BEFORE}; a connection without an expiry is never renewed. */
    public static boolean needsRenewal(Instant expiresAt, Instant now) {
        return expiresAt != null && expiresAt.minus(RENEW_BEFORE).isBefore(now);
    }

    /** The first reason the grant is unusable, a missing scope before a missing refresh token; {@code null} when complete. */
    public static GrantFault grantFault(Collection<String> grantedScopes, String refreshToken) {
        if (!missingScopes(grantedScopes).isEmpty()) {
            return GrantFault.SCOPE_MISSING;
        }
        if (refreshToken == null || refreshToken.isBlank()) {
            return GrantFault.NO_REFRESH_TOKEN;
        }
        return null;
    }

    /** The required scopes absent from {@code granted}, in the order of {@link #SCOPES}. */
    public static List<String> missingScopes(Collection<String> granted) {
        return SCOPES.stream().filter(scope -> granted == null || !granted.contains(scope)).toList();
    }

    /** The consent URL for offline access with PKCE S256; every value is URL-encoded. */
    public static String authorizationUrl(GoogleProperties properties, String state, String codeChallenge) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("client_id", properties.clientId());
        query.put("redirect_uri", properties.redirectUri());
        query.put("response_type", "code");
        query.put("scope", String.join(" ", SCOPES));
        query.put("state", state);
        query.put("code_challenge", codeChallenge);
        query.put("code_challenge_method", "S256");
        query.put("access_type", "offline");
        query.put("prompt", "consent");
        query.put("include_granted_scopes", "true");
        List<String> pairs = new ArrayList<>();
        query.forEach((name, value) -> pairs.add(encode(name) + "=" + encode(value)));
        return properties.authBaseUrl() + "?" + String.join("&", pairs);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
