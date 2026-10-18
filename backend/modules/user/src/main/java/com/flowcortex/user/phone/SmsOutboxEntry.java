package com.flowcortex.user.phone;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** An SMS the development sender kept instead of delivering it; an ADMIN reads them in the console. */
@Entity
@Table(name = "sms_outbox")
public class SmsOutboxEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "phone_e164", nullable = false, updatable = false)
    private String phoneE164;

    @Column(name = "body", nullable = false, updatable = false, columnDefinition = "text")
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SmsOutboxEntry() {
    }

    public SmsOutboxEntry(UUID id, String phoneE164, String body, Instant createdAt) {
        this.id = id;
        this.phoneE164 = phoneE164;
        this.body = body;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getPhoneE164() {
        return phoneE164;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
