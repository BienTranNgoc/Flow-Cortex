-- Help Center tables. No FK to app_user (another module's table): user ids are plain uuids.
-- FKs inside the module cascade, except an article's group (a group holding articles cannot be
-- deleted) and a request's conversation (it expires on its own; the request keeps a null link).
-- Guest session ids and network addresses are stored only as keyed HMAC-SHA256 hashes.

CREATE TABLE support_topic_group (
    id          uuid         PRIMARY KEY,
    name        text         NOT NULL UNIQUE CHECK (char_length(name) BETWEEN 1 AND 60),
    position    integer      NOT NULL,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL
);

-- search_text is title + body lower-cased with diacritics stripped, written on every save.
CREATE TABLE support_article (
    id                 uuid         PRIMARY KEY,
    group_id           uuid         NOT NULL REFERENCES support_topic_group (id) ON DELETE RESTRICT,
    slug               text         NOT NULL UNIQUE
                                    CHECK (char_length(slug) <= 80 AND slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    title              text         NOT NULL CHECK (char_length(title) BETWEEN 1 AND 150),
    body               text         NOT NULL CHECK (char_length(body) BETWEEN 1 AND 20000),
    search_text        text         NOT NULL,
    status             text         NOT NULL CHECK (status IN ('draft', 'published', 'archived')),
    published_at       timestamptz  NULL,
    featured_position  smallint     NULL CHECK (featured_position BETWEEN 1 AND 3),
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    CHECK (status <> 'archived' OR featured_position IS NULL)
);

CREATE UNIQUE INDEX uq_support_article_featured_position
    ON support_article (featured_position) WHERE featured_position IS NOT NULL;
CREATE INDEX idx_support_article_group_id ON support_article (group_id);

CREATE TABLE support_ai_conversation (
    id                  uuid         PRIMARY KEY,
    owner_user_id       uuid         NULL,
    guest_session_hash  text         NULL,
    last_message_at     timestamptz  NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CHECK ((owner_user_id IS NULL) <> (guest_session_hash IS NULL))
);

CREATE INDEX idx_support_ai_conversation_owner
    ON support_ai_conversation (owner_user_id, last_message_at DESC) WHERE owner_user_id IS NOT NULL;
CREATE INDEX idx_support_ai_conversation_guest
    ON support_ai_conversation (guest_session_hash, last_message_at DESC) WHERE guest_session_hash IS NOT NULL;
CREATE INDEX idx_support_ai_conversation_last_message_at ON support_ai_conversation (last_message_at);

CREATE TABLE support_ai_message (
    id               uuid         PRIMARY KEY,
    conversation_id  uuid         NOT NULL REFERENCES support_ai_conversation (id) ON DELETE CASCADE,
    role             text         NOT NULL CHECK (role IN ('user', 'assistant')),
    text             text         NOT NULL,
    outcome          text         NULL CHECK (outcome IN ('answered', 'no_answer', 'blocked')),
    cited_slugs      text[]       NOT NULL DEFAULT '{}',
    created_at       timestamptz  NOT NULL
);

CREATE INDEX idx_support_ai_message_conversation ON support_ai_message (conversation_id, created_at);

-- One row per quota subject and day; a charge is one conditional upsert on this key.
CREATE TABLE support_quota_usage (
    subject_key  text     NOT NULL,
    day          date     NOT NULL,
    count        integer  NOT NULL CHECK (count >= 0),
    PRIMARY KEY (subject_key, day)
);

CREATE TABLE support_request (
    id                  uuid         PRIMARY KEY,
    number              bigint       GENERATED ALWAYS AS IDENTITY UNIQUE,
    requester_id        uuid         NOT NULL,
    type                text         NOT NULL CHECK (type IN ('account', 'credit', 'workflow', 'technical', 'other')),
    subject             text         NOT NULL CHECK (char_length(subject) BETWEEN 5 AND 120),
    status              text         NOT NULL
                                     CHECK (status IN ('new', 'in_progress', 'waiting_member', 'resolved', 'closed')),
    ai_conversation_id  uuid         NULL REFERENCES support_ai_conversation (id) ON DELETE SET NULL,
    -- Stays true after the linked conversation expires, so "expired" can be told from "never linked".
    ai_conversation_linked  boolean  NOT NULL DEFAULT false,
    -- The language the member filed the request in; the reply mail goes out in it.
    locale              text         NOT NULL DEFAULT 'vi',
    member_unread       boolean      NOT NULL,
    last_activity_at    timestamptz  NOT NULL,
    resolved_at         timestamptz  NULL,
    resolved_seen_at    timestamptz  NULL,
    closed_reason       text         NULL CHECK (closed_reason IN ('cancelled', 'confirmed', 'auto', 'admin')),
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CHECK ((status = 'closed') = (closed_reason IS NOT NULL))
);

CREATE INDEX idx_support_request_requester ON support_request (requester_id, last_activity_at DESC);
CREATE INDEX idx_support_request_status ON support_request (status, last_activity_at DESC);
CREATE INDEX idx_support_request_ai_conversation ON support_request (ai_conversation_id)
    WHERE ai_conversation_id IS NOT NULL;

CREATE TABLE support_message (
    id           uuid         PRIMARY KEY,
    request_id   uuid         NOT NULL REFERENCES support_request (id) ON DELETE CASCADE,
    author_kind  text         NOT NULL CHECK (author_kind IN ('member', 'admin')),
    author_id    uuid         NOT NULL,
    body         text         NOT NULL CHECK (char_length(body) BETWEEN 1 AND 5000),
    created_at   timestamptz  NOT NULL
);

CREATE INDEX idx_support_message_request ON support_message (request_id, created_at);

CREATE TABLE support_attachment (
    id            uuid         PRIMARY KEY,
    message_id    uuid         NOT NULL REFERENCES support_message (id) ON DELETE CASCADE,
    bucket        text         NOT NULL,
    object_key    text         NOT NULL,
    content_type  text         NOT NULL,
    size_bytes    bigint       NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 5242880),
    position      smallint     NOT NULL CHECK (position BETWEEN 1 AND 3),
    created_at    timestamptz  NOT NULL,
    UNIQUE (message_id, position)
);

-- Written in the same transaction as the create or reply it guards; purged after 24 hours.
-- target_id is the request a reply was posted on, null for a create.
CREATE TABLE support_idempotency (
    requester_id  uuid         NOT NULL,
    idem_key      text         NOT NULL,
    kind          text         NOT NULL,
    target_id     uuid         NULL,
    result_id     uuid         NOT NULL,
    created_at    timestamptz  NOT NULL,
    PRIMARY KEY (requester_id, idem_key)
);

CREATE INDEX idx_support_idempotency_created_at ON support_idempotency (created_at);

-- The console's assistant switch; the environment switch still gates the assistant on top of it.
CREATE TABLE support_settings (
    id                 smallint     PRIMARY KEY CHECK (id = 1),
    assistant_enabled  boolean      NOT NULL,
    updated_by         uuid         NULL,
    updated_at         timestamptz  NOT NULL
);

INSERT INTO support_settings (id, assistant_enabled, updated_by, updated_at) VALUES (1, true, NULL, now());

-- Six attempts in all: the first send and five retries.
CREATE TABLE support_mail_outbox (
    id                 uuid         PRIMARY KEY,
    request_id         uuid         NOT NULL REFERENCES support_request (id) ON DELETE CASCADE,
    message_id         uuid         NOT NULL REFERENCES support_message (id) ON DELETE CASCADE,
    recipient_user_id  uuid         NOT NULL,
    attempts           smallint     NOT NULL CHECK (attempts BETWEEN 0 AND 6),
    next_attempt_at    timestamptz  NOT NULL,
    sent_at            timestamptz  NULL,
    last_error         text         NULL,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL
);

CREATE INDEX idx_support_mail_outbox_due ON support_mail_outbox (next_attempt_at)
    WHERE sent_at IS NULL AND attempts < 6;
