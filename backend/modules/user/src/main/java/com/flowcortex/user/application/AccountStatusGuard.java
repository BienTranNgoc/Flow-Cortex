package com.flowcortex.user.application;

import com.flowcortex.shared.web.AccountLockedException;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.UserStatus;
import java.time.Instant;
import org.springframework.security.access.AccessDeniedException;

/**
 * The gates that can refuse a sign-in before a token is minted: {@link #requireActive} on status,
 * {@link #requireNotLocked} on the lockout window, and {@link #canSignIn} for callers that answer
 * with a result instead of an exception. Used by every {@code application} class holding an
 * {@link AppUser} after lookup — {@link UserDirectoryImpl}, {@link AuthServiceImpl}, {@link
 * JwtRefreshTokenService} and {@link UserServiceImpl}. {@code UsersController} authorizes purely
 * off the JWT role claim, so the services re-check status: a suspended ADMIN with a still-valid
 * token must not keep full CRUD for the token's remaining TTL.
 */
final class AccountStatusGuard {

    private AccountStatusGuard() {
    }

    static void requireActive(AppUser user) {
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new AccessDeniedException("Account is suspended");
        }
    }

    static void requireNotLocked(AppUser user, Instant now) {
        if (isLocked(user, now)) {
            Instant lockedUntil = user.getLockedUntil();
            throw new AccountLockedException("Account is locked until " + lockedUntil, lockedUntil);
        }
    }

    static boolean canSignIn(AppUser user, Instant now) {
        return user.getStatus() == UserStatus.ACTIVE && !isLocked(user, now);
    }

    private static boolean isLocked(AppUser user, Instant now) {
        Instant lockedUntil = user.getLockedUntil();
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
