package com.flowcortex.user.infrastructure;

import com.flowcortex.user.domain.AppUser;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link AppUser}.
 *
 * <p>{@link #findByOwnerIdAndId} is the cross-scope enforcement point: a lookup outside the
 * caller's owner scope returns empty, turned into a 404 by the application layer — invisible,
 * not merely forbidden.
 *
 * <p>{@link #findByEmail} is deliberately NOT owner-scoped: login is by bare email (global
 * uniqueness) and case-insensitive; the DB's unique index on {@code lower(email)} is the actual
 * race-safe enforcement.
 */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    @Query("SELECT u FROM AppUser u WHERE lower(u.email) = lower(:email)")
    Optional<AppUser> findByEmail(@Param("email") String email);

    /** Row-locked read, so concurrent failed logins each see the counter the previous one wrote. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM AppUser u WHERE u.id = :id")
    Optional<AppUser> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByEmailIgnoreCase(String email);

    Optional<AppUser> findByGoogleSubject(String googleSubject);

    Optional<AppUser> findByOwnerIdAndEmail(UUID ownerId, String email);

    Optional<AppUser> findByOwnerIdAndId(UUID ownerId, UUID id);

    List<AppUser> findAllByOwnerIdOrderByEmailAsc(UUID ownerId);

    /** Ids and emails only, whatever the status: the rest of the row, the password hash included, stays unread. */
    @Query("SELECT new com.flowcortex.user.infrastructure.UserEmail(u.id, u.email) FROM AppUser u WHERE u.id IN :ids")
    List<UserEmail> findEmailsByIdIn(@Param("ids") Collection<UUID> ids);
}
