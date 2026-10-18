package com.flowcortex.credit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A member's credit balance, never below zero. Only read through this entity: every change is one
 * conditional statement in {@code CreditLedgerRepository}, paired with its ledger row.
 */
@Entity
@Table(name = "credit_wallet")
public class CreditWallet {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "balance", nullable = false)
    private long balance;

    protected CreditWallet() {
    }

    public UUID getUserId() {
        return userId;
    }

    public long getBalance() {
        return balance;
    }
}
