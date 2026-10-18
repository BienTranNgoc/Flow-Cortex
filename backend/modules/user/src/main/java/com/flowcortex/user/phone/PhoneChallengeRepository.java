package com.flowcortex.user.phone;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Codes sent to numbers. Every state change is a conditional update whose row count says whether
 * this caller won: a code counts at most {@link PhoneChallenge#MAX_ATTEMPTS} wrong entries and is
 * consumed at most once, however many requests race on it.
 */
public interface PhoneChallengeRepository extends JpaRepository<PhoneChallenge, UUID> {

    /** When codes went to this number after {@code since}, whoever asked — the send caps are per number. */
    @Query("SELECT c.createdAt FROM PhoneChallenge c WHERE c.phoneE164 = :phone AND c.createdAt > :since")
    List<Instant> findSendTimesSince(@Param("phone") String phoneE164, @Param("since") Instant since);

    Optional<PhoneChallenge> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    /** A send closes every earlier challenge, so a user holds at most one open challenge. */
    Optional<PhoneChallenge> findFirstByUserIdAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByCreatedAtDesc(
            UUID userId);

    /** The member's earlier codes, newest first: a code entered from one of them is stale, not wrong. */
    List<PhoneChallenge> findTop5ByUserIdAndIdNotOrderByCreatedAtDesc(UUID userId, UUID id);

    @Modifying
    @Query("UPDATE PhoneChallenge c SET c.invalidatedAt = :now "
            + "WHERE c.userId = :userId AND c.consumedAt IS NULL AND c.invalidatedAt IS NULL")
    int invalidateOpen(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE PhoneChallenge c SET c.attempts = c.attempts + 1 WHERE c.id = :id AND c.attempts < "
            + PhoneChallenge.MAX_ATTEMPTS + " AND c.consumedAt IS NULL AND c.invalidatedAt IS NULL")
    int countWrongAttempt(@Param("id") UUID id);

    @Query("SELECT c.attempts FROM PhoneChallenge c WHERE c.id = :id")
    int findAttempts(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE PhoneChallenge c SET c.invalidatedAt = :now WHERE c.id = :id AND c.invalidatedAt IS NULL")
    int invalidate(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE PhoneChallenge c SET c.consumedAt = :now WHERE c.id = :id AND c.attempts < "
            + PhoneChallenge.MAX_ATTEMPTS + " AND c.consumedAt IS NULL AND c.invalidatedAt IS NULL")
    int consume(@Param("id") UUID id, @Param("now") Instant now);
}
