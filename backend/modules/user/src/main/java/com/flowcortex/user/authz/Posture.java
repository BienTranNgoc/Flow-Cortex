package com.flowcortex.user.authz;

import com.flowcortex.shared.security.authz.Permission;
import java.util.Set;

/** What a handler declares about who may call it, read from its method annotations. */
sealed interface Posture {

    /** No posture annotation: the handler is not governed yet. */
    record None() implements Posture {
    }

    /** More than one posture annotation, or a key posture with no key: refused for everyone. */
    record Conflict() implements Posture {
    }

    record Public() implements Posture {
    }

    record AuthenticatedOnly() implements Posture {
    }

    record AllOf(Set<Permission> keys) implements Posture {
        public AllOf {
            keys = Set.copyOf(keys);
        }
    }

    record AnyOf(Set<Permission> keys) implements Posture {
        public AnyOf {
            keys = Set.copyOf(keys);
        }
    }
}
