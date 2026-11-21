package com.flowcortex.user.authz;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.application.AccountStatusGuard;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the facts the admin decision needs: the caller's row by primary key and, for an active staff
 * role, the role's keys. A super admin or an end user costs one statement, staff two.
 */
@Component
public class CallerFactsLoader {

    private final AppUserRepository users;
    private final RolePermissions rolePermissions;

    CallerFactsLoader(AppUserRepository users, RolePermissions rolePermissions) {
        this.users = users;
        this.rolePermissions = rolePermissions;
    }

    /** 404 {@code user.current.notFound} when the row is gone; 403 for a suspended account. */
    @Transactional(readOnly = true)
    public CallerFacts load(UUID userId) {
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.current.notFound", userId)));
        AccountStatusGuard.requireActive(user);
        Role role = user.getRole();
        boolean adminDisabled = user.getAdminDisabledAt() != null && (role.isSuperAdmin() || role.isStaff());
        Set<Permission> keys = role.isStaff() && !adminDisabled ? rolePermissions.keysOf(role) : Set.of();
        return new CallerFacts(role.isSuperAdmin(), adminDisabled, keys);
    }
}
