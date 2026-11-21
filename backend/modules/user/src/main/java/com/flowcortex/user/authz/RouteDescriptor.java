package com.flowcortex.user.authz;

import java.util.Set;

/**
 * One mapped request route as the startup check sees it: how many posture annotations its handler
 * carries and the keys they name.
 */
record RouteDescriptor(String method, String pattern, int postures, Set<String> keys) {

    RouteDescriptor {
        keys = Set.copyOf(keys);
    }

    String label() {
        return method + " " + pattern;
    }
}
