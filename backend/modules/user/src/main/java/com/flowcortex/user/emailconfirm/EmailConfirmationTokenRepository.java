package com.flowcortex.user.emailconfirm;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository for {@link EmailConfirmationToken}; every spend is one conditional UPDATE. */
public interface EmailConfirmationTokenRepository extends JpaRepository<EmailConfirmationToken, UUID> {

    /**
     * 1 when this call spent an open, unexpired token of this purpose held by this user; 0 when it
     * was unknown, of another purpose or user, used or expired.
     */
    @Modifying
    @Query("UPDATE EmailConfirmationToken t SET t.usedAt = :now "
            + "WHERE t.tokenHash = :tokenHash AND t.purpose = :purpose AND t.userId = :userId"
            + " AND t.usedAt IS NULL AND t.expiresAt > :now")
    int consume(@Param("tokenHash") String tokenHash, @Param("purpose") EmailLinkPurpose purpose,
            @Param("userId") UUID userId, @Param("now") Instant now);

    @Query("SELECT t.userId FROM EmailConfirmationToken t WHERE t.tokenHash = :tokenHash AND t.purpose = :purpose")
    Optional<UUID> findUserIdByTokenHash(@Param("tokenHash") String tokenHash,
            @Param("purpose") EmailLinkPurpose purpose);

    /** Retires every token of the user that is still open, whatever its purpose; earlier mails stop working. */
    @Modifying
    @Query("UPDATE EmailConfirmationToken t SET t.usedAt = :now WHERE t.userId = :userId AND t.usedAt IS NULL")
    int invalidateOpenFor(@Param("userId") UUID userId, @Param("now") Instant now);
}
