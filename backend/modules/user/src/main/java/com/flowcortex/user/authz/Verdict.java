package com.flowcortex.user.authz;

import com.flowcortex.shared.web.AdminDisabledException;
import org.springframework.security.access.AccessDeniedException;

enum Verdict {
    ALLOW,
    DENY,
    ADMIN_DISABLED;

    /** Throws the exception the problem-details advice renders for this verdict; nothing for {@link #ALLOW}. */
    void requireAllowed() {
        if (this == DENY) {
            throw new AccessDeniedException("Missing permission");
        }
        if (this == ADMIN_DISABLED) {
            throw new AdminDisabledException();
        }
    }
}
