package com.flowcortex.user.phone;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * One code sent to a number. Only its BCrypt hash is stored. Attempts, consumption and
 * invalidation change through the conditional updates of {@link PhoneChallengeRepository}, never
 * through this entity, so two concurrent entries cannot both count or both succeed. A verified
 * {@link Purpose#CHANGE} code also records the number it replaced and until when that can be undone.
 */
@Entity
@Table(name = "phone_challenge")
public class PhoneChallenge {

    static final int MAX_ATTEMPTS = 5;

    /** What a code proves: a first verification, or a change of the verified number. */
    public enum Purpose {
        VERIFY,
        CHANGE
    }

    /** Stores {@link Purpose} as its lower-case name, the form the check constraint allows. */
    @Converter
    static class PurposeConverter implements AttributeConverter<Purpose, String> {

        @Override
        public String convertToDatabaseColumn(Purpose attribute) {
            return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
        }

        @Override
        public Purpose convertToEntityAttribute(String dbData) {
            return dbData == null ? null : Purpose.valueOf(dbData.toUpperCase(Locale.ROOT));
        }
    }

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

    @Convert(converter = PurposeConverter.class)
    @Column(name = "purpose", nullable = false, updatable = false)
    private Purpose purpose;

    @Column(name = "prior_phone_e164", insertable = false, updatable = false)
    private String priorPhoneE164;

    @Column(name = "undo_until", insertable = false, updatable = false)
    private Instant undoUntil;

    @Column(name = "reverted_at", insertable = false, updatable = false)
    private Instant revertedAt;

    protected PhoneChallenge() {
    }

    public PhoneChallenge(UUID id, UUID userId, String phoneE164, String codeHash, Instant expiresAt,
            Instant createdAt) {
        this(id, userId, phoneE164, codeHash, expiresAt, createdAt, Purpose.VERIFY);
    }

    public PhoneChallenge(UUID id, UUID userId, String phoneE164, String codeHash, Instant expiresAt,
            Instant createdAt, Purpose purpose) {
        this.id = id;
        this.userId = userId;
        this.phoneE164 = phoneE164;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.purpose = purpose;
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

    public Purpose getPurpose() {
        return purpose;
    }

    public String getPriorPhoneE164() {
        return priorPhoneE164;
    }

    public Instant getUndoUntil() {
        return undoUntil;
    }

    public Instant getRevertedAt() {
        return revertedAt;
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
