package com.flowcortex.user.infrastructure;

import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * The id alone, so the entity is first loaded by {@link #findByIdForUpdate}: an entity already in
     * the persistence context would keep the state it was read with, not the locked row's.
     */
    @Query("SELECT u.id FROM AppUser u WHERE lower(u.email) = lower(:email)")
    Optional<UUID> findIdByEmail(@Param("email") String email);

    /** Row-locked read, so concurrent failed logins each see the counter the previous one wrote. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM AppUser u WHERE u.id = :id")
    Optional<AppUser> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Row-locks every active super admin in id order (so two removals never wait on each other in
     * opposite orders); the second of two concurrent removals re-reads the set the first shrank.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT u FROM AppUser u
            WHERE u.role = com.flowcortex.user.domain.Role.ADMIN
              AND u.status = com.flowcortex.user.domain.UserStatus.ACTIVE
              AND u.adminDisabledAt IS NULL
            ORDER BY u.id""")
    List<AppUser> lockActiveSuperAdmins();

    /**
     * Accounts holding one of {@code roles}, across every owner scope, newest first. {@code pattern}
     * is a lower-case {@code LIKE} pattern whose wildcards the caller escaped with {@code !}.
     */
    @Query(value = """
            SELECT u FROM AppUser u
            WHERE u.role IN :roles
              AND (lower(u.email) LIKE :pattern ESCAPE '!' OR lower(u.name) LIKE :pattern ESCAPE '!')
            ORDER BY u.createdAt DESC, u.id DESC""",
            countQuery = """
            SELECT count(u) FROM AppUser u
            WHERE u.role IN :roles
              AND (lower(u.email) LIKE :pattern ESCAPE '!' OR lower(u.name) LIKE :pattern ESCAPE '!')""")
    Page<AppUser> searchByRoles(@Param("roles") Collection<Role> roles, @Param("pattern") String pattern,
            Pageable pageable);

    Optional<AppUser> findByGoogleSubject(String googleSubject);

    Optional<AppUser> findByOwnerIdAndEmail(UUID ownerId, String email);

    Optional<AppUser> findByOwnerIdAndId(UUID ownerId, UUID id);

    List<AppUser> findAllByOwnerIdOrderByEmailAsc(UUID ownerId);

    /** Ids and emails only, whatever the status: the rest of the row, the password hash included, stays unread. */
    @Query("SELECT new com.flowcortex.user.infrastructure.UserEmail(u.id, u.email) FROM AppUser u WHERE u.id IN :ids")
    List<UserEmail> findEmailsByIdIn(@Param("ids") Collection<UUID> ids);
}
