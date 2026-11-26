package com.flowcortex.user.authz;

import com.flowcortex.shared.web.AdminDisabledException;
import com.flowcortex.shared.web.MfaRequiredException;
import java.util.function.BooleanSupplier;
import org.springframework.security.access.AccessDeniedException;

enum Verdict {
    ALLOW,
    DENY,
    ADMIN_DISABLED,
    MFA_REQUIRED;

    /**
     * Throws the exception the problem-details advice renders for this verdict; nothing for {@link #ALLOW}.
     * {@code enrolled} is asked only for {@link #MFA_REQUIRED}: whether the account already has TOTP on.
     */
    void requireAllowed(BooleanSupplier enrolled) {
        if (this == DENY) {
            throw new AccessDeniedException("Missing permission");
        }
        if (this == ADMIN_DISABLED) {
            throw new AdminDisabledException();
        }
        if (this == MFA_REQUIRED) {
            throw new MfaRequiredException(enrolled.getAsBoolean());
        }
    }
}
