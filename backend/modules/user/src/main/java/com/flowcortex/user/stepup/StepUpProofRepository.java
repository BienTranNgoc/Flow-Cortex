package com.flowcortex.user.stepup;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Step-up proofs. A proof is spent by one conditional write whose row says whether this caller won. */
public interface StepUpProofRepository extends JpaRepository<StepUpProof, UUID> {

    /** Columns {@link #spend} returns from the row it just spent. */
    interface SpentRow {

        UUID getUserId();

        String getPurpose();

        Instant getExpiresAt();
    }

    /**
     * Marks the unspent proof with this hash spent and returns it; empty when the hash is unknown or
     * already spent. Row locking makes two concurrent callers see one row between them.
     */
    @Query(value = """
            UPDATE step_up_proof SET consumed_at = :now
            WHERE token_hash = :hash AND consumed_at IS NULL
            RETURNING user_id AS userId, purpose AS purpose, expires_at AS expiresAt
            """, nativeQuery = true)
    Optional<SpentRow> spend(@Param("hash") String tokenHash, @Param("now") Instant now);

    default Optional<ConsumedProof> consume(String tokenHash, Instant now) {
        return spend(tokenHash, now).map(row -> new ConsumedProof(row.getUserId(), row.getPurpose(), row.getExpiresAt()));
    }

    /** Deletes every proof that was spent or has expired; returns how many. */
    @Modifying
    @Query("DELETE FROM StepUpProof p WHERE p.consumedAt IS NOT NULL OR p.expiresAt < :now")
    int deleteSpentBefore(@Param("now") Instant now);
}
