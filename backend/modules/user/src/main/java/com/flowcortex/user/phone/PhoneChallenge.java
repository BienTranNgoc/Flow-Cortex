package com.flowcortex.user.phone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One code sent to a number. Only its BCrypt hash is stored. Attempts, consumption and
 * invalidation change through the conditional updates of {@link PhoneChallengeRepository}, never
 * through this entity, so two concurrent entries cannot both count or both succeed.
 */
@Entity
@Table(name = "phone_challenge")
public class PhoneChallenge {

    static final int MAX_ATTEMPTS = 5;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "phone_e164", nullable = false, updatable = false)
    private String phoneE164;

    @Column(name = "code_hash", nullable = false, updatable = false)
    private String codeHash;

    @Column(name = "attempts", nullable = false, insertable = false, updatable = false)
    private int attempts;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at", insertable = false, updatable = false)
    private Instant consumedAt;

    @Column(name = "invalidated_at", insertable = false, updatable = false)
    private Instant invalidatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PhoneChallenge() {
    }

    public PhoneChallenge(UUID id, UUID userId, String phoneE164, String codeHash, Instant expiresAt,
            Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.phoneE164 = phoneE164;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getPhoneE164() {
        return phoneE164;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public Instant getInvalidatedAt() {
        return invalidatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isLocked() {
        return attempts >= MAX_ATTEMPTS;
    }

    public boolean isOpen() {
        return consumedAt == null && invalidatedAt == null && !isLocked();
    }

    /** Valid up to and including {@code expiresAt}. */
    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
