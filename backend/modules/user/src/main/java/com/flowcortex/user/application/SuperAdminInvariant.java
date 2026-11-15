package com.flowcortex.user.application;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The system always keeps one active super admin: an account with role {@code ADMIN}, status
 * {@code ACTIVE} and no deactivation.
 */
@Component
public class SuperAdminInvariant {

    private final AppUserRepository users;

    SuperAdminInvariant(AppUserRepository users) {
        this.users = users;
    }

    /**
     * Refuses the removal of {@code target} from the active super admins when it would leave none.
     * Must run inside the caller's transaction and before any setter on {@code target}: the locking
     * query auto-flushes pending changes, which would already show the target as removed. The locked
     * rows make two concurrent removals serialize, so the second one sees the shrunk set.
     *
     * @throws ConflictException {@code user.lastSuperAdmin}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertNotLastActive(AppUser target) {
        if (!isActiveSuperAdmin(target)) {
            return;
        }
        boolean anotherRemains = users.lockActiveSuperAdmins().stream()
                .anyMatch(other -> !other.getId().equals(target.getId()));
        if (!anotherRemains) {
            throw new ConflictException(new LocalizedMessage("user.lastSuperAdmin"));
        }
    }

    private static boolean isActiveSuperAdmin(AppUser user) {
        return user.getRole().isSuperAdmin() && user.getStatus() == UserStatus.ACTIVE && !user.isAdminDisabled();
    }
}
