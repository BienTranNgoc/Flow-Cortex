package com.flowcortex.user.infrastructure;

import com.flowcortex.user.domain.AppUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link AppUser}.
 *
 * <p>{@link #findByOwnerIdAndId} is the enforcement point for cross-scope
 * reads: a lookup outside the caller's own owner scope returns empty, which
 * the application layer turns into a 404 ({@code NotFoundException}) rather
 * than a 403 — a cross-scope record is invisible, not merely forbidden.
 *
 * <p>{@link #findByEmail} is deliberately NOT owner-scoped: login is by bare
 * email with no scope input, and email uniqueness is global — the {@code
 * UNIQUE (email)} constraint in {@code V2026.09.22.001__user.sql} is the
 * actual race-safe enforcement, this method only reads.
 */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByEmail(String email);

    Optional<AppUser> findByOwnerIdAndEmail(UUID ownerId, String email);

    Optional<AppUser> findByOwnerIdAndId(UUID ownerId, UUID id);

    List<AppUser> findAllByOwnerIdOrderByEmailAsc(UUID ownerId);
}
