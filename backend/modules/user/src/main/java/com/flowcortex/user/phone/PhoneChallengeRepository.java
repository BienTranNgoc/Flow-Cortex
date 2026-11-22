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

    /** When codes went to this number after {@code since}, whoever asked: the per-number ceiling. */
    @Query("SELECT c.createdAt FROM PhoneChallenge c WHERE c.phoneE164 = :phone AND c.createdAt > :since")
    List<Instant> findSendTimesSince(@Param("phone") String phoneE164, @Param("since") Instant since);

    /** When this member sent codes to this number after {@code since}: the caps and the cooldown ladder. */
    @Query("SELECT c.createdAt FROM PhoneChallenge c "
            + "WHERE c.userId = :userId AND c.phoneE164 = :phone AND c.createdAt > :since")
    List<Instant> findSendTimesByUserSince(@Param("userId") UUID userId, @Param("phone") String phoneE164,
            @Param("since") Instant since);

    Optional<PhoneChallenge> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<PhoneChallenge> findFirstByUserIdAndPurposeOrderByCreatedAtDesc(UUID userId,
            PhoneChallenge.Purpose purpose);

    /** A send closes every earlier challenge, so a user holds at most one open challenge. */
    Optional<PhoneChallenge> findFirstByUserIdAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByCreatedAtDesc(
            UUID userId);

    /** The newest open code of one purpose: each verify path reads only its own. */
    Optional<PhoneChallenge>
            findFirstByUserIdAndPurposeAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByCreatedAtDesc(
                    UUID userId, PhoneChallenge.Purpose purpose);

    /** The member's earlier codes, newest first: a code entered from one of them is stale, not wrong. */
    List<PhoneChallenge> findTop5ByUserIdAndIdNotOrderByCreatedAtDesc(UUID userId, UUID id);

    List<PhoneChallenge> findTop5ByUserIdAndPurposeAndIdNotOrderByCreatedAtDesc(UUID userId,
            PhoneChallenge.Purpose purpose, UUID id);

    /** The member's newest verified change whose undo window is still open and not yet reverted. */
    default Optional<PhoneChallenge> findLatestUndoable(UUID userId, Instant now) {
        return findLatestUndoableOf(userId, PhoneChallenge.Purpose.CHANGE, now);
    }

    @Query("SELECT c FROM PhoneChallenge c WHERE c.userId = :userId AND c.purpose = :purpose "
            + "AND c.consumedAt IS NOT NULL AND c.priorPhoneE164 IS NOT NULL AND c.revertedAt IS NULL "
            + "AND c.undoUntil > :now ORDER BY c.createdAt DESC LIMIT 1")
    Optional<PhoneChallenge> findLatestUndoableOf(@Param("userId") UUID userId,
            @Param("purpose") PhoneChallenge.Purpose purpose, @Param("now") Instant now);

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

    /** Records the number a consumed change code replaced and until when the change can be undone. */
    @Modifying
    @Query("UPDATE PhoneChallenge c SET c.priorPhoneE164 = :priorPhone, c.undoUntil = :undoUntil "
            + "WHERE c.id = :id AND c.consumedAt IS NOT NULL AND c.priorPhoneE164 IS NULL")
    int markChanged(@Param("id") UUID id, @Param("priorPhone") String priorPhone,
            @Param("undoUntil") Instant undoUntil);

    /** Closes the undo window of a change; 0 rows means it was already reverted. */
    @Modifying
    @Query("UPDATE PhoneChallenge c SET c.revertedAt = :now WHERE c.id = :id AND c.revertedAt IS NULL")
    int markReverted(@Param("id") UUID id, @Param("now") Instant now);
}
