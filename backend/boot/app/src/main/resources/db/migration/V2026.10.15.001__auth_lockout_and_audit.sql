-- Account lockout (G1): failed_login_attempts + locked_until on app_user.
-- Auth audit (G5): auth_event table for the 8 AuthEventType values, user_id nullable (no FK
-- to app_user: keeps the no-FK-cross-module rule from personal.md §1.3 — events for unknown
-- emails still record here with user_id NULL).
--
-- Two partial/conditional indexes serve the two filter shapes the admin endpoint exposes:
-- user-scoped lookups (user_id + occurred_at DESC) and type-wide reports (type + occurred_at DESC).

ALTER TABLE app_user
    ADD COLUMN failed_login_attempts smallint NOT NULL DEFAULT 0,
    ADD COLUMN locked_until timestamptz;

CREATE INDEX idx_app_user_locked_until ON app_user (locked_until) WHERE locked_until IS NOT NULL;

CREATE TABLE auth_event (
    id           uuid         PRIMARY KEY,
    user_id      uuid         NULL,
    type         varchar(40)  NOT NULL,
    ip           varchar(64)  NULL,
    user_agent   varchar(512) NULL,
    occurred_at  timestamptz  NOT NULL,
    metadata     jsonb        NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_auth_event_user_id_occurred_at ON auth_event (user_id, occurred_at DESC);
CREATE INDEX idx_auth_event_type_occurred_at    ON auth_event (type, occurred_at DESC);
