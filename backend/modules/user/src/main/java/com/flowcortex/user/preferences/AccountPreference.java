package com.flowcortex.user.preferences;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One row per account: the saved language and the {@code ui} and {@code privacy} JSON objects. */
@Entity
@Table(name = "account_preference")
class AccountPreference {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "locale")
    private String locale;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ui", nullable = false)
    private String ui;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "privacy", nullable = false)
    private String privacy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountPreference() {
    }

    String getLocale() {
        return locale;
    }

    String getUi() {
        return ui;
    }

    String getPrivacy() {
        return privacy;
    }

    void setLocale(String locale, Instant now) {
        this.locale = locale;
        this.updatedAt = now;
    }

    void setUi(String ui, Instant now) {
        this.ui = ui;
        this.updatedAt = now;
    }

    void setPrivacy(String privacy, Instant now) {
        this.privacy = privacy;
        this.updatedAt = now;
    }
}
