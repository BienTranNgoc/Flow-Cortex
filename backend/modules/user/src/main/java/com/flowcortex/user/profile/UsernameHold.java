package com.flowcortex.user.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A released username that only its former owner may take back until {@code expiresAt}. */
@Entity
@Table(name = "username_hold")
class UsernameHold {

    @Id
    @Column(name = "username", nullable = false, updatable = false)
    private String username;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UsernameHold() {
    }

    String getUsername() {
        return username;
    }

    UUID getUserId() {
        return userId;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }
}
