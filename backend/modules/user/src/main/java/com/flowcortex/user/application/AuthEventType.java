package com.flowcortex.user.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Closed enum of every audit row the user module can write. B1's first 8
 * values; B3's google-oauth-and-refresh-tokens change adds 7 more — see
 * {@code openspec/changes/google-oauth-and-refresh-tokens/specs/identity/spec.md}
 * for each one's contract.
 */
public enum AuthEventType {

    LOGIN_SUCCESS,
    LOGIN_FAILED_BAD_CREDENTIALS,
    LOGIN_FAILED_ACCOUNT_LOCKED,
    LOGIN_FAILED_ACCOUNT_SUSPENDED,
    REGISTRATION_ACCEPTED,
    REGISTRATION_REJECTED_RATE_LIMITED,
    PASSWORD_RESET_REQUESTED,
    PASSWORD_RESET_COMPLETED,

    OAUTH_LOGIN_SUCCESS,
    OAUTH_ACCOUNT_LINKED,
    OAUTH_LOGIN_FAILED_INVALID_STATE,
    OAUTH_LOGIN_FAILED_INVALID_ID_TOKEN,
    OAUTH_LINK_BLOCKED_PASSWORD_REQUIRED,

    REFRESH_TOKEN_ROTATED,
    REFRESH_TOKEN_REUSE_DETECTED,

    LOGOUT;

    public static Map<String, Object> metadata(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("metadata() requires an even number of arguments");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
        }
        return map;
    }

    public static Map<String, Object> userMetadata(UUID userId) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (userId != null) {
            map.put("userId", userId.toString());
        }
        return map;
    }
}
