package com.flowcortex.user.api;

import java.util.List;
import java.util.UUID;

/**
 * Public API backing {@code /api/v1/users}. Every method takes the calling account's id: an {@code ADMIN}
 * acts in the owner scope of its own id (self-owned, or delegated by another admin), staff act in the scope
 * of the {@code ADMIN} that owns them. Out-of-scope lookup/update/delete throws 404, not disclosing
 * cross-scope existence. A change that needs a key the caller's role lacks throws 403 and writes nothing.
 */
public interface UserService {

    /**
     * Creates a self-owned ADMIN, i.e. a new owner scope. Seeder-only, not exposed over HTTP.
     * @throws ConflictException if the email is already taken
     */
    UserView createInitialAdmin(String email, String rawPassword, String name);

    /**
     * Creates a new user under the caller's owner scope.
     * @param role any role code
     * @throws ConflictException if the email is already taken (unique constraint; concurrent calls race, one wins)
     */
    UserView createUser(UUID callerId, String email, String rawPassword, String name, String role);

    /** @throws NotFoundException if {@code id} does not exist within the caller's owner scope */
    UserView getUser(UUID callerId, UUID id);

    List<UserView> listUsers(UUID callerId);

    /**
     * Partial update: a {@code null} argument leaves that field unchanged. Unlike {@link AuthService#updateOwnProfile}
     * (self-service, name only), this admin op can also edit {@code email}/{@code role}/{@code status}.
     * @throws NotFoundException if {@code id} does not exist within the caller's owner scope
     * @throws ConflictException if {@code email} is supplied and already taken, or the change would remove the
     *         last active super admin or the registration owner
     */
    UserView updateUser(UUID callerId, UUID id, String email, String name, String role, String status);

    /**
     * @throws NotFoundException if {@code id} does not exist within the caller's owner scope
     * @throws ConflictException if the target is the caller, the last active super admin or the registration owner
     */
    void deleteUser(UUID callerId, UUID id);
}
