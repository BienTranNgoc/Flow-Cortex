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
 * An application user. Distilled from DigiShield's {@code AppUser}
 * (tenant/MFA/risk fields dropped — IP boundary, `docs/PLAN.md` §10) and
 * re-scoped to {@code owner_id} rather than a tenant id: an ADMIN row is
 * self-owned ({@code owner_id == id}); every other row's {@code owner_id} is
 * the administering ADMIN's id.
 *
 * <p>{@code passwordHash} is never exposed outside this module — the {@code
 * api} views ({@link com.flowcortex.user.api.CurrentUser}, {@link
 * com.flowcortex.user.api.UserView}) simply do not carry it.
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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected AppUser() {
    }

    public AppUser(UUID id, UUID ownerId, String email, String passwordHash, String name, Role role,
            UserStatus status) {
        this.id = id;
        this.ownerId = ownerId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.role = role;
        this.status = status;
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
}
