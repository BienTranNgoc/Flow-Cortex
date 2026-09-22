package com.flowcortex.user.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Cross-module lookup port (design.md D2): resolves a user id — typically a
 * JWT subject already authenticated by {@code :shared:security} — into a
 * {@link CurrentUser} view. Deliberately not owner-scoped: the caller's
 * identity was already established by a verified signature, so a lookup by
 * that same id is not a cross-scope disclosure (unlike {@code
 * UserService}'s owner-scoped CRUD, which is what R-025's 404 guards).
 *
 * <p>This is the handle {@code :modules:workflow} depends on {@code user ::
 * api} for — "who is the current caller" — without depending on this
 * module's internal JPA entity or repository.
 *
 * <p>Also the per-request suspension gate (R-021/R-023): a SUSPENDED row
 * throws {@link org.springframework.security.access.AccessDeniedException}
 * (403) rather than resolving — every caller of this port refuses a
 * suspended principal on its very next call, not just at login.
 */
public interface UserDirectory {

    Optional<CurrentUser> findById(UUID id);
}
