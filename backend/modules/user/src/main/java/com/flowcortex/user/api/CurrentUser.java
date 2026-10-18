package com.flowcortex.user.api;

import java.util.UUID;

/**
 * Immutable view of an authenticated user, exposed to the web layer and other modules — {@code
 * workflow} resolves "who is calling" through this port, not {@code user}'s JPA entity. Never
 * carries the password hash.
 *
 * @param id           user id; the JWT subject, and the ownership column value other modules stamp
 * @param ownerId      administering ADMIN's id (self for an ADMIN row)
 * @param email        login email
 * @param name         display name
 * @param role         {@code ADMIN} or {@code MEMBER}
 * @param status       {@code ACTIVE} or {@code SUSPENDED}
 * @param hasPassword  the account can sign in with a password
 * @param googleLinked the account can sign in with Google
 */
public record CurrentUser(UUID id, UUID ownerId, String email, String name, String role, String status,
        boolean hasPassword, boolean googleLinked) {

    /** No way to sign in again once this session ends, so the member must set a password first. */
    public boolean passwordSetupRequired() {
        return !hasPassword && !googleLinked;
    }
}
