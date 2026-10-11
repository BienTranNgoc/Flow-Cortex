package com.flowcortex.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An application user, scoped by {@code owner_id} rather than a tenant id: an ADMIN row is
 * self-owned ({@code owner_id == id}); every other row's {@code owner_id} is the administering
 * ADMIN's id.
 *
 * <p>{@code passwordHash} is never exposed outside this module — the {@code api} views ({@link
 * com.flowcortex.user.api.CurrentUser}, {@link com.flowcortex.user.api.UserView}) don't carry it.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private UserStatus status;

    @Column(name = "failed_login_attempts", nullable = false)
    private short failedLoginAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "token_version", nullable = false)
    private long tokenVersion;

    @Column(name = "google_subject")
    private String googleSubject;

    @Column(name = "has_password", nullable = false)
    private boolean hasPassword;

    @Column(name = "oauth_provider")
    private String oauthProvider;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected AppUser() {
    }

    public AppUser(UUID id, UUID ownerId, String email, String passwordHash, String name, Role role,
            UserStatus status) {
        this(id, ownerId, email, passwordHash, name, role, status, true);
    }

    /** Constructor that exposes {@code hasPassword} — used by the OAuth-only path. */
    public AppUser(UUID id, UUID ownerId, String email, String passwordHash, String name, Role role,
            UserStatus status, boolean hasPassword) {
        this.id = id;
        this.ownerId = ownerId;
        this.email = email;
        this.passwordHash = passwordHash == null ? "" : passwordHash;
        this.name = name;
        this.role = role;
        this.status = status;
        this.hasPassword = hasPassword;
        this.tokenVersion = 0L;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public Role getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public short getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public long getTokenVersion() {
        return tokenVersion;
    }

    public String getGoogleSubject() {
        return googleSubject;
    }

    public boolean hasPassword() {
        return hasPassword;
    }

    public String getOauthProvider() {
        return oauthProvider;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Every mutator below bumps {@code updatedAt} — the single place that invariant lives. */
    private void touch() {
        this.updatedAt = Instant.now();
    }

    public void changeEmail(String email) {
        this.email = email;
        touch();
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        touch();
    }

    public void changeName(String name) {
        this.name = name;
        touch();
    }

    public void changeRole(Role role) {
        this.role = role;
        touch();
    }

    public void changeStatus(UserStatus status) {
        this.status = status;
        touch();
    }

    /**
     * Increments the failed-login counter and locks the account once it reaches {@code
     * maxAttempts}. A lockout window already in effect is left as-is — a lock-out during a
     * lock-out would just slide the wall forward, which an attacker would use to keep the
     * account permanently locked.
     */
    public void recordFailedLogin(int maxAttempts, java.time.Duration lockoutDuration, Instant now) {
        this.failedLoginAttempts = (short) Math.min(this.failedLoginAttempts + 1, Short.MAX_VALUE);
        touch();
        if (this.lockedUntil == null && this.failedLoginAttempts >= maxAttempts) {
            this.lockedUntil = now.plus(lockoutDuration);
        }
    }

    /** Successful login clears the counter and any in-effect lockout window. */
    public void resetFailedLoginAttempts(Instant now) {
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    /** Bump after a logout so previously-issued access JWTs (carrying the old `tv`) fail validation. */
    public void bumpTokenVersion() {
        this.tokenVersion = this.tokenVersion + 1L;
        touch();
    }

    /** Link a verified Google subject to this account. Idempotent on the same subject. */
    public void linkGoogle(String subject) {
        this.googleSubject = subject;
        if (this.oauthProvider == null) {
            this.oauthProvider = "google";
        }
        touch();
    }

    public void unlinkGoogle() {
        this.googleSubject = null;
        this.oauthProvider = null;
        touch();
    }
}
