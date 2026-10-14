package com.flowcortex.user.infrastructure;

import com.flowcortex.user.domain.RefreshToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link RefreshToken}. The bulk-update methods are intentionally
 * idempotent (no row-count check that aborts) — a no-op for already-revoked rows
 * is the right behaviour for family-revoke on reuse detection.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE RefreshToken t SET t.usedAt = :now, t.replacedById = :replacedBy "
            + "WHERE t.id = :id AND t.usedAt IS NULL")
    int markUsed(@Param("id") UUID id, @Param("now") Instant now, @Param("replacedBy") UUID replacedBy);

    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now "
            + "WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.userId = :userId AND t.revokedAt IS NULL")
    int revokeAllByUserId(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE (t.revokedAt IS NOT NULL AND t.revokedAt < :cutoff) "
            + "OR t.expiresAt < :cutoff")
    int deleteExpiredAndRevokedBefore(@Param("cutoff") Instant cutoff);

    /** One row per live session (rotation retires the previous row), oldest first. */
    @Query("SELECT t FROM RefreshToken t WHERE t.userId = :userId AND t.revokedAt IS NULL "
            + "AND t.usedAt IS NULL AND t.expiresAt > :now ORDER BY t.issuedAt ASC")
    List<RefreshToken> findActiveByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
