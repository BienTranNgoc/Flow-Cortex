package com.flowcortex.user.authz;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Checks every mapped route against the posture rules; the result lists each breach, empty when sound. */
final class AuthzInventory {

    private AuthzInventory() {
    }

    static List<String> violations(List<RouteDescriptor> routes, Set<String> seededKeys) {
        List<String> violations = new ArrayList<>();
        for (RouteDescriptor route : routes) {
            if (route.postures() > 1) {
                violations.add(route.label() + " declares " + route.postures() + " postures; exactly one is allowed");
            } else if (route.postures() == 0 && GovernedPaths.isGoverned(route.pattern())) {
                violations.add(route.label() + " is an admin route with no posture annotation");
            }
            for (String key : new TreeSet<>(route.keys())) {
                if (!seededKeys.contains(key)) {
                    violations.add(route.label() + " names permission key '" + key + "' which is not seeded");
                }
            }
        }
        return violations;
    }
}
