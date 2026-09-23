package com.flowcortex.user.application;

import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.UserStatus;
import org.springframework.security.access.AccessDeniedException;

/**
 * The suspension gate: the single place "a suspended principal performs no
 * authenticated action" lives, shared by every {@code application} class
 * that has an {@link AppUser} in hand after a lookup —
 * {@link UserDirectoryImpl} (identity resolution behind {@code /auth/me} and
 * every workflow endpoint), {@link AuthServiceImpl} ({@code login}, {@code
 * updateOwnProfile}), and {@link UserServiceImpl} (every {@code
 * /api/v1/users} operation, gating the CALLER's own row — {@code
 * UsersController} authorizes purely off the JWT role claim and never
 * consulted this gate before, so a suspended ADMIN with a still-valid token
 * kept full CRUD there for the token's remaining TTL).
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
