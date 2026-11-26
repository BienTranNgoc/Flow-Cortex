package com.flowcortex.user.oauth;

import java.util.Optional;

/**
 * The ordered checks of an authorization request. A request that names no registered client or
 * redirect URI is refused outright, because nothing may be redirected to it; any other fault is an OAuth
 * error the page sends to the verified redirect URI.
 */
final class AuthorizationRequestCheck {

    static final String SCOPE = "mcp";

    private AuthorizationRequestCheck() {
    }

    record Params(String responseType, String clientId, String redirectUri, String codeChallenge,
            String codeChallengeMethod, String scope, String state, String resource) {
    }

    sealed interface Outcome permits Refused, Checked {
    }

    enum Fault {
        UNKNOWN_CLIENT,
        UNREGISTERED_REDIRECT_URI
    }

    /** Nothing may be redirected: the page shows a problem instead. */
    record Refused(Fault fault) implements Outcome {
    }

    /** The client and redirect URI are verified; {@code error} is the OAuth error to send there, or null. */
    record Checked(OAuthClient client, String redirectUri, String scope, String error) implements Outcome {
    }

    static Outcome check(Params params, Optional<OAuthClient> registered) {
        if (registered.isEmpty()) {
            return new Refused(Fault.UNKNOWN_CLIENT);
        }
        OAuthClient client = registered.get();
        if (params.redirectUri() == null || client.redirectUris().stream()
                .noneMatch(uri -> RedirectUris.matches(uri, params.redirectUri()))) {
            return new Refused(Fault.UNREGISTERED_REDIRECT_URI);
        }
        return new Checked(client, params.redirectUri(), SCOPE, errorOf(params));
    }

    private static String errorOf(Params params) {
        if (!"code".equals(params.responseType())) {
            return "unsupported_response_type";
        }
        if (!Pkce.validChallenge(params.codeChallenge()) || !"S256".equals(params.codeChallengeMethod())) {
            return "invalid_request";
        }
        if (params.scope() != null && !params.scope().isBlank() && !SCOPE.equals(params.scope().strip())) {
            return "invalid_scope";
        }
        return null;
    }
}
