package com.flowcortex.user.phone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A member's verified phone number — one row per user, so verifying another number replaces this
 * one. A number has at most one verified owner (partial unique index on {@code phone_e164}).
 */
@Entity
@Table(name = "user_phone")
public class UserPhone {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "phone_e164", nullable = false)
    private String phoneE164;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    protected UserPhone() {
    }

    public UserPhone(UUID userId, String phoneE164, Instant verifiedAt) {
        this.userId = userId;
        this.phoneE164 = phoneE164;
        this.verifiedAt = verifiedAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getPhoneE164() {
        return phoneE164;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public void verify(String phoneE164, Instant verifiedAt) {
        this.phoneE164 = phoneE164;
        this.verifiedAt = verifiedAt;
    }
}
