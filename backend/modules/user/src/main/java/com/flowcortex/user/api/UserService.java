package com.flowcortex.user.api;

import java.util.List;
import java.util.UUID;

/**
 * Public API backing {@code /api/v1/users} (ADMIN-only); every method scopes by {@code ownerId} (an ADMIN row is
 * self-owned, {@code owner_id == id}). Out-of-scope lookup/update/delete throws 404, not disclosing cross-scope existence.
 */
public interface UserService {

    /**
     * Creates a self-owned ADMIN, i.e. a new owner scope. Seeder-only, not exposed over HTTP.
     * @throws ConflictException if the email is already taken
     */
    UserView createInitialAdmin(String email, String rawPassword, String name);

    /**
     * Creates a new user under {@code ownerId}'s scope.
     * @param role {@code ADMIN} or {@code MEMBER}
     * @throws ConflictException if the email is already taken (unique constraint; concurrent calls race, one wins)
     */
    UserView createUser(UUID ownerId, String email, String rawPassword, String name, String role);

    /** @throws NotFoundException if {@code id} does not exist within {@code ownerId}'s scope */
    UserView getUser(UUID ownerId, UUID id);

    List<UserView> listUsers(UUID ownerId);

    /**
     * Partial update: a {@code null} argument leaves that field unchanged. Unlike {@link AuthService#updateOwnProfile}
     * (self-service, name only), this admin op can also edit {@code email}/{@code role}/{@code status}.
     * @throws NotFoundException if {@code id} does not exist within {@code ownerId}'s scope
     * @throws ConflictException if {@code email} is supplied and already taken
     */
    UserView updateUser(UUID ownerId, UUID id, String email, String name, String role, String status);

    /** @throws NotFoundException if {@code id} does not exist within {@code ownerId}'s scope */
    void deleteUser(UUID ownerId, UUID id);
}
