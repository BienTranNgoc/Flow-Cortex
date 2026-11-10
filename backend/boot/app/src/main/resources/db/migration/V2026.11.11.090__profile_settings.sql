-- Profile settings owned by the user module: the username-change cooldown, released-name holds and
-- per-account preferences.
--
-- username_hold is keyed by name, so a name released many times is still one row: taking a name
-- deletes its row and the next release overwrites an expired one. Growth is bounded by the number of
-- distinct released names (each account releases at most one per cooldown window), so no purge job
-- is needed.

ALTER TABLE app_user ADD COLUMN username_changed_at timestamptz NULL;

CREATE TABLE username_hold (
    username    varchar(20) PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    expires_at  timestamptz NOT NULL,
    created_at  timestamptz NOT NULL
);

CREATE TABLE account_preference (
    user_id     uuid        PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    locale      varchar(2)  NULL CHECK (locale IN ('vi', 'en')),
    ui          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    privacy     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    updated_at  timestamptz NOT NULL
);
