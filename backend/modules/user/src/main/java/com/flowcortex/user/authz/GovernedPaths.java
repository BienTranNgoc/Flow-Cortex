package com.flowcortex.user.authz;

/** Which request paths are admin routes that must declare a posture. */
public final class GovernedPaths {

    private static final String ADMIN_PREFIX = "/api/v1/admin/";
    private static final String USERS = "/api/v1/users";

    private GovernedPaths() {
    }

    /** {@code /api/v1/admin/**}, {@code /api/v1/users} and below it; {@code /api/v1/usersettings} is not governed. */
    public static boolean isGoverned(String pattern) {
        return pattern != null
                && (pattern.startsWith(ADMIN_PREFIX) || pattern.equals(USERS) || pattern.startsWith(USERS + "/"));
    }
}
