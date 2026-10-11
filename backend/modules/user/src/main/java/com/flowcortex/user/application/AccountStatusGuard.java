package com.flowcortex.user.application;

import com.flowcortex.shared.web.AccountLockedException;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.UserStatus;
import java.time.Instant;
import org.springframework.security.access.AccessDeniedException;

/**
 * The two gates that can refuse a login before the token is minted:
 * {@link #requireActive} on status, {@link #requireNotLocked} on the lockout window. Used by
 * every {@code application} class holding an {@link AppUser} after lookup — {@link
 * UserDirectoryImpl}, {@link AuthServiceImpl}, and {@link UserServiceImpl}. Before
 * {@code requireNotLocked}, {@code UsersController} authorized purely off the JWT role claim,
 * so a suspended ADMIN with a still-valid token kept full CRUD there for the token's
 * remaining TTL.
 */
final class AccountStatusGuard {

    private AccountStatusGuard() {
    }

    static void requireActive(AppUser user) {
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new AccessDeniedException("Account is suspended");
        }
    }

    static void requireNotLocked(AppUser user) {
        Instant lockedUntil = user.getLockedUntil();
        if (lockedUntil != null && lockedUntil.isAfter(Instant.now())) {
            throw new AccountLockedException("Account is locked until " + lockedUntil, lockedUntil);
        }
    }
}
