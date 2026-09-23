package com.flowcortex.user.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Cross-module lookup port: resolves a user id (typically an already-authenticated JWT subject)
 * into a {@link CurrentUser} view. Deliberately not owner-scoped — identity was already
 * established by a verified signature, unlike {@code UserService}'s owner-scoped CRUD.
 *
 * <p>The handle {@code :modules:workflow} uses for "who is the current caller" without depending
 * on this module's JPA entity or repository.
 *
 * <p>Also the per-request suspension gate: a SUSPENDED row throws {@link
 * org.springframework.security.access.AccessDeniedException} (403) instead of resolving.
 */
public interface UserDirectory {

    Optional<CurrentUser> findById(UUID id);
}
