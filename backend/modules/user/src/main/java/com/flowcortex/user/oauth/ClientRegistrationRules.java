package com.flowcortex.user.oauth;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a client may ask for at registration (RFC 7591): authorization-code clients with PKCE, secret
 * by form or Basic, or none. {@code refresh_token} in the requested grant types is tolerated because
 * MCP clients send it; the answer always lists {@code authorization_code} only.
 */
final class ClientRegistrationRules {

    static final String DEFAULT_NAME = "MCP client";
    static final int MAX_NAME_LENGTH = 128;
    static final String INVALID_REDIRECT_URI = "invalid_redirect_uri";
    static final String INVALID_CLIENT_METADATA = "invalid_client_metadata";

    private static final String AUTHORIZATION_CODE = "authorization_code";
    private static final Set<String> TOLERATED_GRANTS = Set.of(AUTHORIZATION_CODE, "refresh_token");
    private static final Set<String> AUTH_METHODS =
            Set.of(OAuthClient.AUTH_POST, OAuthClient.AUTH_BASIC, OAuthClient.AUTH_NONE);

    private ClientRegistrationRules() {
    }

    record Request(String clientName, List<String> redirectUris, List<String> grantTypes,
            List<String> responseTypes, String tokenEndpointAuthMethod) {
    }

    sealed interface Outcome permits Valid, Invalid {
    }

    record Valid(String clientName, List<String> redirectUris, String authMethod) implements Outcome {
    }

    record Invalid(String error, String description) implements Outcome {
    }

    static Outcome evaluate(Request request) {
        Optional<String> uriProblem = RedirectUris.validateForRegistration(request.redirectUris());
        if (uriProblem.isPresent()) {
            return new Invalid(INVALID_REDIRECT_URI, uriProblem.get());
        }
        if (request.grantTypes() != null && !grantsAreSupported(request.grantTypes())) {
            return metadata("grant_types must include authorization_code and nothing beyond refresh_token");
        }
        if (request.responseTypes() != null
                && (request.responseTypes().isEmpty() || !request.responseTypes().stream().allMatch("code"::equals))) {
            return metadata("response_types must be [\"code\"]");
        }
        String method = request.tokenEndpointAuthMethod() == null ? OAuthClient.AUTH_POST
                : request.tokenEndpointAuthMethod();
        if (!AUTH_METHODS.contains(method)) {
            return metadata("token_endpoint_auth_method must be one of client_secret_post, client_secret_basic, none");
        }
        String name = cleanName(request.clientName());
        if (name.length() > MAX_NAME_LENGTH) {
            return metadata("client_name is longer than " + MAX_NAME_LENGTH + " characters");
        }
        return new Valid(name.isEmpty() ? DEFAULT_NAME : name, List.copyOf(request.redirectUris()), method);
    }

    private static boolean grantsAreSupported(List<String> grants) {
        return grants.contains(AUTHORIZATION_CODE) && TOLERATED_GRANTS.containsAll(grants);
    }

    private static Invalid metadata(String description) {
        return new Invalid(INVALID_CLIENT_METADATA, description);
    }

    private static String cleanName(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder(raw.length());
        raw.codePoints().filter(cp -> !Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT)
                .forEach(kept::appendCodePoint);
        return kept.toString().strip();
    }
}
