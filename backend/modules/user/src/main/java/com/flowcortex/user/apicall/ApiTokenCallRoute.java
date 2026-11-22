package com.flowcortex.user.apicall;

import com.flowcortex.shared.security.PersonalAccessTokenAuthenticator;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.jwt.Jwt;

/** Which requests the call audit acts on, and the stable route label a recorded request is filed under. */
final class ApiTokenCallRoute {

    static final int MAX_LENGTH = 200;
    static final String WORKFLOWS_PATH = "/api/v1/workflows";

    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Pattern UUID_SEGMENT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern NUMERIC_SEGMENT = Pattern.compile("[0-9]+");

    private ApiTokenCallRoute() {}

    /** A write sent with a personal access token; sessions, anonymous callers and reads are not recorded. */
    static boolean acts(String method, Object principal) {
        return WRITES.contains(method) && principal instanceof Jwt jwt && PersonalAccessTokenAuthenticator.isPat(jwt);
    }

    static boolean isWorkflowCreate(String method, String path) {
        return "POST".equals(method) && WORKFLOWS_PATH.equals(path);
    }

    /** The matched pattern when Spring resolved one, else the normalized path. */
    static String of(String matchedPattern, String path) {
        if (matchedPattern != null && !matchedPattern.isBlank()) {
            return cut(matchedPattern);
        }
        return normalize(path);
    }

    /** The path without its query, UUID and all-digit segments replaced by {@code {id}}, cut to {@value #MAX_LENGTH}. */
    static String normalize(String path) {
        String bare = path == null ? "" : path;
        int query = bare.indexOf('?');
        if (query >= 0) {
            bare = bare.substring(0, query);
        }
        String[] segments = bare.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (UUID_SEGMENT.matcher(segments[i]).matches() || NUMERIC_SEGMENT.matcher(segments[i]).matches()) {
                segments[i] = "{id}";
            }
        }
        return cut(String.join("/", segments));
    }

    private static String cut(String route) {
        return route.length() <= MAX_LENGTH ? route : route.substring(0, MAX_LENGTH);
    }
}
