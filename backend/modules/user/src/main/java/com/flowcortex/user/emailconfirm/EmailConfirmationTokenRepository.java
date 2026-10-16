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

    /** 1 when this call spent an open, unexpired token; 0 when it was unknown, used or expired. */
    @Modifying
    @Query("UPDATE EmailConfirmationToken t SET t.usedAt = :now "
            + "WHERE t.tokenHash = :tokenHash AND t.usedAt IS NULL AND t.expiresAt > :now")
    int consume(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    @Query("SELECT t.userId FROM EmailConfirmationToken t WHERE t.tokenHash = :tokenHash")
    Optional<UUID> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

    /** Retires every link of the user that is still open; earlier mails stop working. */
    @Modifying
    @Query("UPDATE EmailConfirmationToken t SET t.usedAt = :now WHERE t.userId = :userId AND t.usedAt IS NULL")
    int invalidateOpenFor(@Param("userId") UUID userId, @Param("now") Instant now);
}
