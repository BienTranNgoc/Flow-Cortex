package com.flowcortex.credit.infrastructure;

import com.flowcortex.credit.domain.CreditLedgerEntry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every balance change is ONE statement that moves the wallet and writes its ledger row together,
 * decided by row count, never read-then-write: the wallet's {@code balance >= :amount} condition
 * stops an overdraft when two charges race, and the partial unique indexes on the ledger make a
 * repeated refund or grant change nothing.
 */
public interface CreditLedgerRepository extends JpaRepository<CreditLedgerEntry, UUID> {

    /** Takes {@code amount} from the wallet only if it holds that much; {@code 1} when charged, {@code 0} when not. */
    @Modifying
    @Query(value = "WITH charged AS (UPDATE credit_wallet SET balance = balance - :amount "
            + "WHERE user_id = :userId AND balance >= :amount RETURNING user_id) "
            + "INSERT INTO credit_ledger (id, user_id, run_id, run_step_id, kind, amount, created_at) "
            + "SELECT :id, user_id, :runId, :runStepId, 'debit', :amount, :now FROM charged", nativeQuery = true)
    int debit(@Param("id") UUID id, @Param("userId") UUID userId, @Param("runId") UUID runId,
            @Param("runStepId") UUID runStepId, @Param("amount") long amount, @Param("now") Instant now);

    /**
     * Gives back the step's debit; {@code 1} when refunded, {@code 0} when the step was never charged
     * or is already refunded (a concurrent second attempt waits on the unique index, then does nothing).
     */
    @Modifying
    @Query(value = "WITH refunded AS (INSERT INTO credit_ledger (id, user_id, run_id, run_step_id, kind, amount, created_at) "
            + "SELECT :id, d.user_id, d.run_id, d.run_step_id, 'refund', d.amount, :now FROM credit_ledger d "
            + "WHERE d.run_step_id = :runStepId AND d.kind = 'debit' "
            + "ON CONFLICT (run_step_id, kind) WHERE run_step_id IS NOT NULL DO NOTHING "
            + "RETURNING user_id, amount) "
            + "UPDATE credit_wallet w SET balance = w.balance + r.amount FROM refunded r WHERE w.user_id = r.user_id",
            nativeQuery = true)
    int refund(@Param("id") UUID id, @Param("runStepId") UUID runStepId, @Param("now") Instant now);

    /** The one signup grant, creating the wallet if needed; {@code 1} when granted, {@code 0} when already granted. */
    @Modifying
    @Query(value = "WITH granted AS (INSERT INTO credit_ledger (id, user_id, kind, amount, created_at) "
            + "VALUES (:id, :userId, 'grant', :amount, :now) "
            + "ON CONFLICT (user_id) WHERE kind = 'grant' DO NOTHING RETURNING user_id, amount) "
            + "INSERT INTO credit_wallet (user_id, balance) SELECT user_id, amount FROM granted "
            + "ON CONFLICT (user_id) DO UPDATE SET balance = credit_wallet.balance + EXCLUDED.balance",
            nativeQuery = true)
    int grant(@Param("id") UUID id, @Param("userId") UUID userId, @Param("amount") long amount,
            @Param("now") Instant now);

    Optional<CreditLedgerEntry> findByRunStepIdAndKind(UUID runStepId, String kind);

    @Query("SELECT COALESCE(SUM(CASE WHEN e.kind = 'debit' THEN e.amount ELSE -e.amount END), 0) "
            + "FROM CreditLedgerEntry e WHERE e.runId = :runId AND e.kind IN ('debit', 'refund')")
    long sumChargedForRun(@Param("runId") UUID runId);
}
