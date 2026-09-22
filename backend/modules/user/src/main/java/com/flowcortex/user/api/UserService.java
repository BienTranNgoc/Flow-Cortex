package com.flowcortex.user.api;

import java.util.List;
import java.util.UUID;

/**
 * Public API backing {@code /api/v1/users} (design.md D1/D3, ADMIN-only,
 * R-023) — every method is scoped by {@code ownerId}, the acting ADMIN's own
 * id (design.md D1: an ADMIN row is self-owned, {@code owner_id == id}).
 *
 * <p>A lookup/update/delete outside {@code ownerId}'s scope throws {@link
 * com.flowcortex.shared.web.NotFoundException} (404, R-025) rather than
 * disclosing that a record exists in another scope.
 */
public interface UserService {

    /**
     * Creates a new, self-owned ADMIN ({@code owner_id == } the new user's own
     * id). This is the ONLY way an owner scope comes into existence — every
     * other user is created via {@link #createUser} under an already-existing
     * ADMIN's scope — so it is what the dev-profile seeder (task 4.8) uses to
     * seed independent owner scopes, not an admin-facing HTTP operation.
     *
     * @throws com.flowcortex.shared.web.ConflictException if the email is
     *         already taken (R-024, global uniqueness)
     */
    UserView createInitialAdmin(String email, String rawPassword, String name);

    /**
     * Creates a new user under {@code ownerId}'s scope.
     *
     * @param role {@code ADMIN} or {@code MEMBER} (domain enum name)
     * @throws com.flowcortex.shared.web.ConflictException if the email is
     *         already taken (R-024, global uniqueness — two concurrent calls
     *         for the same email race on the database's {@code UNIQUE
     *         (email)} constraint; exactly one wins)
     */
    UserView createUser(UUID ownerId, String email, String rawPassword, String name, String role);

    /**
     * @throws com.flowcortex.shared.web.NotFoundException if {@code id} does
     *         not exist within {@code ownerId}'s scope (R-025)
     */
    UserView getUser(UUID ownerId, UUID id);

    List<UserView> listUsers(UUID ownerId);

    /**
     * Partial update: a {@code null} argument leaves that field unchanged.
     * {@code email}/{@code role}/{@code status} are all admin-editable here —
     * unlike {@link AuthService#updateOwnProfile}, which only ever touches
     * {@code name} for the caller's own row.
     *
     * @throws com.flowcortex.shared.web.NotFoundException if {@code id} does
     *         not exist within {@code ownerId}'s scope (R-025)
     * @throws com.flowcortex.shared.web.ConflictException if {@code email} is
     *         supplied and already taken by another user (R-024)
     */
    UserView updateUser(UUID ownerId, UUID id, String email, String name, String role, String status);

    /**
     * @throws com.flowcortex.shared.web.NotFoundException if {@code id} does
     *         not exist within {@code ownerId}'s scope (R-025)
     */
    void deleteUser(UUID ownerId, UUID id);
}
