package com.flowcortex.user.apicall;

import com.flowcortex.user.pat.PatRoutePolicy;
import org.springframework.http.HttpMethod;
import org.springframework.modulith.NamedInterface;

/**
 * Which routes the personal access token call guards act on, from the same primitives the filter and
 * the route policy use, so the published contract is built from them instead of a second list.
 * {@code route} is the pattern Spring resolved, which is the OpenAPI path template.
 */
@NamedInterface("guards")
public final class ApiTokenCallGuards {

    private ApiTokenCallGuards() {}

    /** A token may reach the route with this method and a repeated request carrying a key is answered from storage. */
    public static boolean takesIdempotencyKey(String method, String route) {
        return IdempotencyKey.appliesTo(method) && reachable(method, route);
    }

    /** A token may reach the route with this method and the write counts against the owner's daily limit. */
    public static boolean countsAgainstDailyLimit(String method, String route) {
        return ApiTokenCallRoute.isWrite(method) && reachable(method, route);
    }

    private static boolean reachable(String method, String route) {
        return PatRoutePolicy.allows(HttpMethod.valueOf(method), route);
    }
}
