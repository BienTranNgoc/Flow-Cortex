ALTER TABLE cv_job_postings
    ADD COLUMN IF NOT EXISTS role_key VARCHAR(200) NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_cv_job_postings_role_key_synced
    ON cv_job_postings (role_key, synced_at DESC);

DELETE FROM cv_job_postings WHERE source IN ('Remotive', 'Arbeitnow');
