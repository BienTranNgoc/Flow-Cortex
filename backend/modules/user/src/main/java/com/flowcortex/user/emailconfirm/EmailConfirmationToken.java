package com.flowcortex.user.emailconfirm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One single-use token of the kind its {@link EmailLinkPurpose} names, stored as the SHA-256 hex of
 * the token — the raw token exists only in the mail or the response that carried it. A token is
 * spent by {@link EmailConfirmationTokenRepository#consume}, never through this entity, so two
 * concurrent opens of the same link cannot both succeed.
 */
@Entity
@Table(name = "email_confirmation_token")
public class EmailConfirmationToken {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, updatable = false)
    private EmailLinkPurpose purpose;

    protected EmailConfirmationToken() {
    }

    EmailConfirmationToken(UUID id, UUID userId, EmailLinkPurpose purpose, String tokenHash, Instant createdAt,
            Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.purpose = purpose;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public EmailLinkPurpose getPurpose() {
        return purpose;
    }
}
