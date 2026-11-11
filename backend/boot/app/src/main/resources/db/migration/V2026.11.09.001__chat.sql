-- Main chat: a member's conversations and their messages. Cross-module owner ids take no foreign key.
-- next_seq hands out message numbers (UPDATE ... SET next_seq = next_seq + 1 ... RETURNING), so a card, a
-- run message and a turn never race for one number.
CREATE TABLE chat_conversation (
    id                uuid         PRIMARY KEY,
    owner_id          uuid         NOT NULL,
    title             varchar(80)  NOT NULL,
    next_seq          int          NOT NULL DEFAULT 1,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    last_activity_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_conversation_owner_activity ON chat_conversation (owner_id, last_activity_at DESC, id DESC);

-- payload holds a card or a run reference. turn_deadline, stop_requested and charge_day belong to the
-- assistant row of a running turn; request_hash fingerprints the send that created the row.
CREATE TABLE chat_message (
    id               uuid         PRIMARY KEY,
    conversation_id  uuid         NOT NULL REFERENCES chat_conversation (id) ON DELETE CASCADE,
    seq              int          NOT NULL,
    role             varchar(16)  NOT NULL CHECK (role IN ('user', 'assistant')),
    kind             varchar(16)  NOT NULL CHECK (kind IN ('text', 'card', 'run', 'error')),
    body             text,
    payload          jsonb,
    status           varchar(16)  NOT NULL CHECK (status IN ('streaming', 'done', 'stopped', 'failed')),
    turn_deadline    timestamptz,
    stop_requested   boolean      NOT NULL DEFAULT false,
    charge_day       date,
    request_hash     text,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_chat_message_seq UNIQUE (conversation_id, seq)
);

-- The database allows one running turn per conversation.
CREATE UNIQUE INDEX uq_chat_message_streaming ON chat_message (conversation_id) WHERE status = 'streaming';

-- Written in the same transaction as the send it guards; purged after 24 hours.
CREATE TABLE chat_idempotency (
    owner_id         uuid         NOT NULL,
    key              text         NOT NULL,
    request_hash     text         NOT NULL,
    conversation_id  uuid,
    message_id       uuid,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, key)
);

CREATE INDEX idx_chat_idempotency_created_at ON chat_idempotency (created_at);

-- Messages per subject (member:<id> or the global ceiling) and day.
CREATE TABLE chat_quota_usage (
    subject  varchar(80)  NOT NULL,
    day      date         NOT NULL,
    count    int          NOT NULL,
    PRIMARY KEY (subject, day)
);

-- The console's chat switch; the environment switch still gates the page on top of it.
CREATE TABLE chat_settings (
    id          smallint     PRIMARY KEY CHECK (id = 1),
    enabled     boolean      NOT NULL,
    updated_by  uuid,
    updated_at  timestamptz
);

INSERT INTO chat_settings (id, enabled, updated_by, updated_at) VALUES (1, true, NULL, now());
