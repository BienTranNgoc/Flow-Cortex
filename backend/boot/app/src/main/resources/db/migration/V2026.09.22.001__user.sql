-- =============================================================================
-- Flow-Cortex - user module (V2026.09.22.001)
--
-- app_user (design.md D1, task 4.1): the first MAIN-source table. Columns and
-- types match the Hibernate-mapped AppUser entity exactly so the app can boot
-- with `spring.jpa.hibernate.ddl-auto: validate` (design.md D7/R-004).
--
-- Ownership scope: `owner_id` is the administering ADMIN's id (an ADMIN row is
-- self-owned, owner_id = own id). Login is by bare email with no scope input,
-- so uniqueness on email is GLOBAL, not per-owner (design.md D1, R-024) — this
-- is what task 4.3's concurrency IT proves: two parallel creates of the same
-- email race on this one constraint, and the database is the sole arbiter.
--
-- No Row-Level Security here (unlike DigiShield's tenant_isolation policy):
-- design.md marks RLS as a later step keyed on owner_id (Out of Scope for this
-- change) — greenfield tables, no data migration, two-way door (design.md D1
-- Reversibility).
-- =============================================================================

CREATE TABLE app_user (
    id            uuid         PRIMARY KEY,
    owner_id      uuid         NOT NULL,
    email         varchar(255) NOT NULL,
    password_hash varchar(255) NOT NULL,
    name          varchar(255) NOT NULL,
    role          varchar(20)  NOT NULL,
    status        varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    CONSTRAINT uq_app_user_email UNIQUE (email)
);

-- Owner-scoped queries (findByOwnerIdAndEmail / findByOwnerIdAndId / the
-- Users-screen list) all filter on owner_id first.
CREATE INDEX idx_app_user_owner_id ON app_user (owner_id);
