package com.flowcortex.user.infrastructure;

import com.flowcortex.user.domain.AppUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link AppUser}.
 *
 * <p>{@link #findByOwnerIdAndId} is the cross-scope enforcement point: a lookup outside the
 * caller's owner scope returns empty, turned into a 404 by the application layer — invisible,
 * not merely forbidden.
 *
 * <p>{@link #findByEmail} is deliberately NOT owner-scoped: login is by bare email (global
 * uniqueness); the DB's {@code UNIQUE (email)} constraint is the actual race-safe enforcement.
 */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByEmail(String email);

    Optional<AppUser> findByOwnerIdAndEmail(UUID ownerId, String email);

    Optional<AppUser> findByOwnerIdAndId(UUID ownerId, UUID id);

    List<AppUser> findAllByOwnerIdOrderByEmailAsc(UUID ownerId);
}
