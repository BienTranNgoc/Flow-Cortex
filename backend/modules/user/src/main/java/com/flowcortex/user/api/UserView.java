package com.flowcortex.user.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public view of an {@code app_user} row returned by {@link UserService}'s
 * CRUD operations. Never carries the password hash.
 *
 * <p>{@code state} is the wire value of the account state; {@code lockedUntil} is {@code null} unless
 * the lockout end is after the instant the view was read; {@code staffTarget} is true when the
 * account's {@code role} row is staff or super admin.
 */
public record UserView(
        UUID id,
        UUID ownerId,
        String email,
        String name,
        String role,
        String status,
        Instant createdAt,
        Instant updatedAt,
        String username,
        String state,
        Instant emailConfirmedAt,
        Instant lockedUntil,
        List<String> signInMethods,
        boolean adminDisabled,
        boolean staffTarget) {

    /** {@code PASSWORD} then {@code GOOGLE}, whichever the account has. */
    public static List<String> signInMethodsOf(boolean hasPassword, boolean linkedToGoogle) {
        if (hasPassword && linkedToGoogle) {
            return List.of("PASSWORD", "GOOGLE");
        }
        if (hasPassword) {
            return List.of("PASSWORD");
        }
        return linkedToGoogle ? List.of("GOOGLE") : List.of();
    }
}
