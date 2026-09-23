-- app_user: columns match the AppUser entity so `ddl-auto: validate` passes.
--
-- owner_id is the administering ADMIN's id (an ADMIN row is self-owned). Login
-- is by bare email with no scope input, so email uniqueness is GLOBAL, not
-- per-owner — the UNIQUE constraint is the sole arbiter when two creates race.
-- No Row-Level Security yet; a later step can key it on owner_id.

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
