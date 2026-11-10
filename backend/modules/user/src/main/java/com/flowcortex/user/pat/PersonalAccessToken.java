package com.flowcortex.user.pat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One personal access token, kept as the SHA-256 hex of the token: the raw value exists only in the
 * create response. Last use and revocation are written by conditional updates in {@link
 * PersonalAccessTokenRepository}, never through this entity.
 */
@Entity
@Table(name = "personal_access_token")
public class PersonalAccessToken {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, updatable = false)
    private String name;

    @Column(name = "token_hash", nullable = false, unique = true, updatable = false)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at", insertable = false, updatable = false)
    private Instant lastUsedAt;

    @Column(name = "revoked_at", insertable = false, updatable = false)
    private Instant revokedAt;

    protected PersonalAccessToken() {
    }

    PersonalAccessToken(UUID id, UUID userId, String name, String tokenHash, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.name = name;
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

    public String getName() {
        return name;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    boolean isLiveAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
