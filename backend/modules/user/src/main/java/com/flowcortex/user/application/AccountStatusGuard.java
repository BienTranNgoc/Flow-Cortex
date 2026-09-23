package com.flowcortex.user.application;

import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.UserStatus;
import org.springframework.security.access.AccessDeniedException;

/**
 * The suspension gate: the one place "a suspended principal performs no authenticated action"
 * lives, used by every {@code application} class holding an {@link AppUser} after lookup —
 * {@link UserDirectoryImpl}, {@link AuthServiceImpl}, and {@link UserServiceImpl}. Before this,
 * {@code UsersController} authorized purely off the JWT role claim, so a suspended ADMIN with a
 * still-valid token kept full CRUD there for the token's remaining TTL.
 */
final class AccountStatusGuard {

    private AccountStatusGuard() {
    }

    static void requireActive(AppUser user) {
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new AccessDeniedException("Account is suspended");
        }
    }
}
