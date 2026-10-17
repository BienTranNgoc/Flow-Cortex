CREATE TABLE media_asset (
    id                  uuid         PRIMARY KEY,
    owner_id            uuid         NOT NULL,
    kind                varchar(30)  NOT NULL,
    bucket              varchar(255) NOT NULL,
    object_key          varchar(1024) NOT NULL,
    mime_type           varchar(255) NOT NULL,
    bytes               bigint       NOT NULL CHECK (bytes >= 0),
    checksum            varchar(255),
    status              varchar(30)  NOT NULL,
    expires_at          timestamptz,
    source_run_step_id  uuid,
    source_attempt      smallint,
    created_at          timestamptz  NOT NULL,
    CONSTRAINT uq_media_asset_object UNIQUE (bucket, object_key),
    CONSTRAINT ck_media_asset_source CHECK (
        (source_run_step_id IS NULL AND source_attempt IS NULL)
        OR (source_run_step_id IS NOT NULL AND source_attempt >= 1)
    )
);

CREATE INDEX idx_media_asset_owner_id ON media_asset (owner_id);
CREATE INDEX idx_media_asset_expires_at ON media_asset (expires_at) WHERE expires_at IS NOT NULL;

CREATE TABLE media_asset_binding (
    run_id       uuid         NOT NULL REFERENCES workflow_run (id),
    binding_key  varchar(100) NOT NULL,
    asset_id     uuid         NOT NULL REFERENCES media_asset (id),
    position     smallint     NOT NULL CHECK (position BETWEEN 0 AND 9),
    created_at   timestamptz  NOT NULL,
    PRIMARY KEY (run_id, binding_key),
    CONSTRAINT uq_media_asset_binding_position UNIQUE (run_id, position)
);

CREATE INDEX idx_media_asset_binding_asset_id ON media_asset_binding (asset_id);
