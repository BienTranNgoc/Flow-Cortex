package com.flowcortex.user.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Public view of an {@code app_user} row returned by {@link UserService}'s
 * CRUD operations. Never carries the password hash.
 */
public record UserView(
        UUID id,
        UUID ownerId,
        String email,
        String name,
        String role,
        String status,
        Instant createdAt,
        Instant updatedAt) {
}
