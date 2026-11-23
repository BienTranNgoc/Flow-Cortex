package com.flowcortex.user.api;

import com.flowcortex.shared.security.authz.Permission;
import java.util.List;
import java.util.UUID;

/**
 * Programmatic key check for a rule inside a handler that the route posture cannot express (a body
 * field that needs a second key). Reads the caller's facts from the database, as the interceptor does.
 */
public interface AdminPermissions {

    /**
     * Returns when the caller is an active super admin or active staff holding {@code key}.
     *
     * @throws org.springframework.security.access.AccessDeniedException when the key is missing
     * @throws com.flowcortex.shared.web.AdminDisabledException          when the caller's admin access is deactivated
     * @throws com.flowcortex.shared.web.NotFoundException               when the caller's row is gone
     */
    void require(UUID userId, Permission key);

    /**
     * The accounts that are active, not deactivated for the admin console, and either super admins or of
     * a role holding {@code key}, ordered by email, at most 500.
     */
    List<AdminAssignee> holders(Permission key);

    /** Whether {@code userId} is one of {@link #holders(Permission)}; false for an unknown account. */
    boolean holds(UUID userId, Permission key);
}
