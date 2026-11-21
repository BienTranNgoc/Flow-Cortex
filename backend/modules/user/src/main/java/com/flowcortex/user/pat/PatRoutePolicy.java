package com.flowcortex.user.pat;

import java.util.List;
import org.springframework.http.HttpMethod;

/**
 * Where a personal access token may act: the one table that decides it, matched on the route
 * pattern Spring resolved. Everything not listed is refused, so a route outside the listed prefixes
 * needs a row here before a token can reach it.
 */
final class PatRoutePolicy {

    private static final List<String> ANY_METHOD_PREFIXES = List.of(
            "/api/v1/workflows",
            "/api/v1/runs",
            "/api/v1/review-tasks",
            "/api/v1/uploads",
            "/api/v1/product-videos",
            "/api/v1/credits");

    private static final String IDENTITY = "/api/v1/auth/me";

    private PatRoutePolicy() {
    }

    static boolean allows(HttpMethod method, String pattern) {
        if (method == null || pattern == null) {
            return false;
        }
        if (IDENTITY.equals(pattern)) {
            return HttpMethod.GET.equals(method);
        }
        return ANY_METHOD_PREFIXES.stream()
                .anyMatch(prefix -> pattern.equals(prefix) || pattern.startsWith(prefix + "/"));
    }
}
