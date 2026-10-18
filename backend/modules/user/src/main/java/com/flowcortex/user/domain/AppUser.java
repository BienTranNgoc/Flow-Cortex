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

    private static final int NAME_MAX_LENGTH = 255;

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

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "username")
    private String username;

    /** {@code null} only for a self-registered account that has not opened its confirmation link yet. */
    @Column(name = "email_confirmed_at")
    private Instant emailConfirmedAt;

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

    /**
     * Constructor that exposes {@code hasPassword} — used by the OAuth-only path. The account is
     * created with its email confirmed: only {@link #selfRegistered} can create an unconfirmed one.
     */
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
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.emailConfirmedAt = now;
    }

    /**
     * An active MEMBER created by self-registration; {@code name} becomes "first last". A {@code
     * null} {@code emailConfirmedAt} leaves the account unable to sign in until {@link #confirmEmail}.
     */
    public static AppUser selfRegistered(UUID id, UUID ownerId, String email, String passwordHash, String firstName,
            String lastName, String username, Instant emailConfirmedAt) {
        AppUser user = new AppUser(id, ownerId, email, passwordHash, firstName + " " + lastName, Role.MEMBER,
                UserStatus.ACTIVE);
        user.firstName = firstName;
        user.lastName = lastName;
        user.username = username;
        user.emailConfirmedAt = emailConfirmedAt;
        return user;
    }

    /**
     * An active MEMBER created by a first Google sign-in: linked to {@code subject}, no password, named
     * as {@link #confirmEmailDroppingUnprovenChoices} names a claimed account.
     */
    public static AppUser googleSignedUp(UUID id, UUID ownerId, String email, String subject, String displayName) {
        AppUser user = new AppUser(id, ownerId, email, "", displayNameOr(displayName, email), Role.MEMBER,
                UserStatus.ACTIVE, false);
        user.linkGoogle(subject);
        return user;
    }

    /** {@code displayName} trimmed, else the email's local part; capped to the column. */
    private static String displayNameOr(String displayName, String email) {
        String chosen = displayName == null || displayName.isBlank() ? emailLocalPart(email) : displayName.trim();
        return chosen.length() <= NAME_MAX_LENGTH ? chosen : chosen.substring(0, NAME_MAX_LENGTH);
    }

    private static String emailLocalPart(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
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

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getUsername() {
        return username;
    }

    public Instant getEmailConfirmedAt() {
        return emailConfirmedAt;
    }

    public boolean isEmailConfirmed() {
        return emailConfirmedAt != null;
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

    /**
     * Confirms the email and drops everything chosen before anyone proved they own the address: the
     * password (with the failed-login count and lockout that guarded it), the username and the names.
     * The display name becomes {@code displayName}, or the email's local part when that is blank.
     * False, changing nothing, when the email was already confirmed.
     */
    public boolean confirmEmailDroppingUnprovenChoices(Instant now, String displayName) {
        if (this.emailConfirmedAt != null) {
            return false;
        }
        this.emailConfirmedAt = now;
        this.passwordHash = "";
        this.hasPassword = false;
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
        this.username = null;
        this.firstName = null;
        this.lastName = null;
        this.name = displayNameOr(displayName, email);
        touch();
        return true;
    }

    /** A new registration of the same, still unconfirmed email replaces everything the earlier one chose. */
    public void replaceUnconfirmedRegistration(String passwordHash, String firstName, String lastName,
            String username, Instant emailConfirmedAt) {
        if (this.emailConfirmedAt != null) {
            throw new IllegalStateException("A confirmed account cannot be registered again");
        }
        this.passwordHash = passwordHash;
        this.hasPassword = true;
        this.firstName = firstName;
        this.lastName = lastName;
        this.name = firstName + " " + lastName;
        this.username = username;
        this.emailConfirmedAt = emailConfirmedAt;
        touch();
    }

    /**
     * First password of an account that has none, with the names and username the member chooses
     * alongside it ({@code username} may be {@code null}); replacing an existing password goes through
     * no path here.
     */
    public void setUpFirstPassword(String passwordHash, String firstName, String lastName, String username) {
        if (this.hasPassword) {
            throw new IllegalStateException("The account already has a password");
        }
        this.passwordHash = passwordHash;
        this.hasPassword = true;
        this.firstName = firstName;
        this.lastName = lastName;
        this.name = firstName + " " + lastName;
        this.username = username;
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
     * account permanently locked. A window that has ended starts the count over, so the account
     * can lock again without a successful login in between.
     */
    public void recordFailedLogin(int maxAttempts, java.time.Duration lockoutDuration, Instant now) {
        if (this.lockedUntil != null && !this.lockedUntil.isAfter(now)) {
            this.failedLoginAttempts = 0;
            this.lockedUntil = null;
        }
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

    /**
     * Link a verified Google subject to this account. Idempotent on the same subject; an account
     * already linked to another subject is never re-pointed.
     */
    public void linkGoogle(String subject) {
        if (this.googleSubject != null && !this.googleSubject.equals(subject)) {
            throw new IllegalStateException("Account is already linked to another Google subject");
        }
        this.googleSubject = subject;
        if (this.oauthProvider == null) {
            this.oauthProvider = "google";
        }
        touch();
    }
}
