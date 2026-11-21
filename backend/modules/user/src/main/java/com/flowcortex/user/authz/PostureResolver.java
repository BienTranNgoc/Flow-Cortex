package com.flowcortex.user.authz;

import com.flowcortex.shared.security.authz.AuthenticatedOnly;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.Public;
import com.flowcortex.shared.security.authz.RequireAnyPermission;
import com.flowcortex.shared.security.authz.RequirePermission;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Set;

/** Reads the posture a handler method declares through the four annotations of {@code shared.security.authz}. */
final class PostureResolver {

    private PostureResolver() {
    }

    /** How many of the four posture annotations the method carries; more than one is a conflict. */
    static int declaredCount(Method method) {
        return (method.isAnnotationPresent(RequirePermission.class) ? 1 : 0)
                + (method.isAnnotationPresent(RequireAnyPermission.class) ? 1 : 0)
                + (method.isAnnotationPresent(Public.class) ? 1 : 0)
                + (method.isAnnotationPresent(AuthenticatedOnly.class) ? 1 : 0);
    }

    static Posture of(Method method) {
        RequirePermission allOf = method.getAnnotation(RequirePermission.class);
        RequireAnyPermission anyOf = method.getAnnotation(RequireAnyPermission.class);
        boolean open = method.isAnnotationPresent(Public.class);
        boolean signedIn = method.isAnnotationPresent(AuthenticatedOnly.class);

        int declared = declaredCount(method);
        if (declared == 0) {
            return new Posture.None();
        }
        if (declared > 1) {
            return new Posture.Conflict();
        }
        if (open) {
            return new Posture.Public();
        }
        if (signedIn) {
            return new Posture.AuthenticatedOnly();
        }
        if (allOf != null) {
            return allOf.value().length == 0 ? new Posture.Conflict() : new Posture.AllOf(keys(allOf.value()));
        }
        return anyOf.value().length == 0 ? new Posture.Conflict() : new Posture.AnyOf(keys(anyOf.value()));
    }

    private static Set<Permission> keys(Permission[] values) {
        return Set.copyOf(EnumSet.of(values[0], values));
    }
}
