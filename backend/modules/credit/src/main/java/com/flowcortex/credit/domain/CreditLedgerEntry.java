package com.flowcortex.credit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One movement of credits: a signup {@code grant}, a step's {@code debit}, or its {@code refund}.
 * Append-only; {@code amount} is always positive and the kind gives the direction.
 */
@Entity
@Table(name = "credit_ledger")
public class CreditLedgerEntry {

    public static final String GRANT = "grant";
    public static final String DEBIT = "debit";
    public static final String REFUND = "refund";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "run_id", updatable = false)
    private UUID runId;

    @Column(name = "run_step_id", updatable = false)
    private UUID runStepId;

    @Column(name = "kind", nullable = false, updatable = false, length = 10)
    private String kind;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CreditLedgerEntry() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getRunStepId() {
        return runStepId;
    }

    public String getKind() {
        return kind;
    }

    public long getAmount() {
        return amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
