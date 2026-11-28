CREATE TABLE cv_job_postings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source          VARCHAR(32)  NOT NULL,
    external_id     VARCHAR(255) NOT NULL,
    title           VARCHAR(300) NOT NULL,
    company         VARCHAR(200) NOT NULL,
    location        VARCHAR(200) NOT NULL,
    salary          VARCHAR(120) NOT NULL DEFAULT '',
    work_mode       VARCHAR(64)  NOT NULL DEFAULT '',
    tags            TEXT         NOT NULL DEFAULT '',
    description     TEXT         NOT NULL DEFAULT '',
    apply_url       TEXT         NOT NULL,
    published_at    TIMESTAMPTZ,
    synced_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (source, external_id)
);

CREATE INDEX idx_cv_job_postings_synced_at ON cv_job_postings (synced_at DESC);
