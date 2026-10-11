package com.flowcortex.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A stateful refresh token, identified by a SHA-256 hash. The raw token never
 * touches this entity — the application layer hashes before persisting. The
 * {@code familyId} groups rotations: a leak anywhere in the chain revokes the
 * whole family.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true, updatable = false)
    private String tokenHash;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @Column(name = "created_ip")
    private String createdIp;

    @Column(name = "user_agent")
    private String userAgent;

    protected RefreshToken() {
    }

    public RefreshToken(UUID id, UUID userId, String tokenHash, UUID familyId, Instant issuedAt,
            Instant expiresAt, String createdIp, String userAgent) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.createdIp = createdIp;
        this.userAgent = userAgent;
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

    public UUID getFamilyId() {
        return familyId;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getReplacedById() {
        return replacedById;
    }

    public String getCreatedIp() {
        return createdIp;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public boolean isExpired(Instant now) {
        return !this.expiresAt.isAfter(now);
    }

    public boolean isUsable(Instant now) {
        return this.usedAt == null && this.revokedAt == null && !isExpired(now);
    }

    public void markUsed(Instant now, UUID replacedById) {
        this.usedAt = now;
        this.replacedById = replacedById;
    }

    public void revoke(Instant now) {
        this.revokedAt = now;
    }
}
