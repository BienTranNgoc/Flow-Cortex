-- Unsubscribe links for care and promotional mail. Additive: two new tables, nothing existing is read or changed.
-- No FK to app_user (another module's table): user ids are plain uuids; a link whose member is gone is
-- detected through the recipient port and answers like a forged one.

-- One row per member, created on the first care or promotional send; both lists live on it.
CREATE TABLE mail_unsubscribe_link (
    id                         uuid         PRIMARY KEY,
    user_id                    uuid         NOT NULL UNIQUE,
    care_unsubscribed_at       timestamptz  NULL,
    marketing_unsubscribed_at  timestamptz  NULL,
    created_at                 timestamptz  NOT NULL
);

-- One row each time a member actually leaves a list; mail_key is null when the link named no catalog key.
CREATE TABLE mail_unsubscribe_history (
    id          uuid         PRIMARY KEY,
    user_id     uuid         NOT NULL,
    list        varchar(16)  NOT NULL CHECK (list IN ('care', 'marketing')),
    source      varchar(16)  NOT NULL CHECK (source IN ('footer_link', 'one_click')),
    mail_key    varchar(64)  NULL,
    created_at  timestamptz  NOT NULL
);

CREATE INDEX mail_unsubscribe_history_user_idx ON mail_unsubscribe_history (user_id);
