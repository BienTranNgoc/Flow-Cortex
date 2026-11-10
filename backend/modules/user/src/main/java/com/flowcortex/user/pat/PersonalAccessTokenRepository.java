package com.flowcortex.user.pat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Personal access tokens; revoking and recording use are single conditional writes. */
public interface PersonalAccessTokenRepository extends JpaRepository<PersonalAccessToken, UUID> {

    Optional<PersonalAccessToken> findByTokenHash(String tokenHash);

    Optional<PersonalAccessToken> findByIdAndUserIdAndRevokedAtIsNull(UUID id, UUID userId);

    /** Everything not revoked, newest first; an expired token stays listed until the retention job removes it. */
    List<PersonalAccessToken> findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(UUID userId);

    @Query("SELECT COUNT(t) FROM PersonalAccessToken t WHERE t.userId = :userId AND t.revokedAt IS NULL"
            + " AND t.expiresAt > :now")
    long countLive(@Param("userId") UUID userId, @Param("now") Instant now);

    /** Revokes the caller's own unrevoked token; 0 when the id is unknown, foreign or already revoked. */
    @Modifying
    @Query("UPDATE PersonalAccessToken t SET t.revokedAt = :now WHERE t.id = :id AND t.userId = :userId"
            + " AND t.revokedAt IS NULL")
    int revoke(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

    /** Writes last use only when it is unset or more than a minute old, so a burst of calls writes once. */
    @Modifying
    @Query(value = """
            UPDATE personal_access_token SET last_used_at = :now
            WHERE id = :id AND (last_used_at IS NULL OR last_used_at < CAST(:now AS timestamptz) - interval '1 minute')
            """, nativeQuery = true)
    int touchLastUsed(@Param("id") UUID id, @Param("now") Instant now);

    /** Deletes revoked tokens and tokens that expired before the cutoff; returns how many. */
    @Modifying
    @Query("DELETE FROM PersonalAccessToken t WHERE t.revokedAt IS NOT NULL OR t.expiresAt < :expiredBefore")
    int deleteDead(@Param("expiredBefore") Instant expiredBefore);
}
