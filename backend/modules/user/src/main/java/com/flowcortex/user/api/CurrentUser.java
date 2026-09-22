package com.flowcortex.user.api;

import java.util.UUID;

/**
 * Immutable view of an authenticated user, exposed to the web layer and to
 * other modules (design.md D2 — {@code workflow} resolves "who is calling"
 * through this port rather than depending on {@code user}'s internal JPA
 * entity). Never carries the password hash (R-021).
 *
 * @param id      the user's own id — this is the value a signed JWT carries
 *                as its subject, and what another module stamps as an
 *                ownership column for "the current caller" (e.g. a workflow
 *                run's owner)
 * @param ownerId the administering ADMIN's id (self for an ADMIN row)
 * @param email   the login email
 * @param name    the display name
 * @param role    {@code ADMIN} or {@code MEMBER} (the domain enum's name)
 * @param status  {@code ACTIVE} or {@code SUSPENDED} (the domain enum's name)
 */
public record CurrentUser(UUID id, UUID ownerId, String email, String name, String role, String status) {
}
