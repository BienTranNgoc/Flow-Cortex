-- One row per admin write; rows outlive their actor, so actor_user_id has no foreign key.
CREATE TABLE admin_audit_event (
    id             uuid         PRIMARY KEY,
    actor_user_id  uuid         NOT NULL,
    actor_email    varchar(255),
    actor_name     varchar(255),
    action         varchar(80)  NOT NULL,
    resource_type  varchar(60)  NOT NULL,
    resource_id    varchar(100),
    resource_label varchar(200),
    change_summary jsonb,
    status         varchar(10)  NOT NULL CHECK (status IN ('success', 'failed')),
    error_code     varchar(120),
    ip             varchar(64),
    user_agent     varchar(512),
    origin_source  varchar(10)  NOT NULL DEFAULT 'unknown'
        CHECK (origin_source IN ('http', 'system', 'unknown')),
    occurred_at    timestamptz  NOT NULL
);

CREATE INDEX idx_admin_audit_event_occurred_at ON admin_audit_event (occurred_at);
