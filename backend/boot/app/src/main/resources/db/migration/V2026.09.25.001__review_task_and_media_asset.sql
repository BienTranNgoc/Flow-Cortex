-- review_task: human-in-the-loop approval checkpoint for workflow executions.
-- When a step requires human verification (e.g. transcript translation review
-- before TTS/rendering), a review_task is created and the run pauses.
--
-- `status`: 'pending' | 'approved' | 'rejected'
CREATE TABLE review_task (
    id                uuid         PRIMARY KEY,
    run_id            uuid         NOT NULL REFERENCES workflow_run (id) ON DELETE CASCADE,
    run_step_id       uuid         NOT NULL REFERENCES run_step (id) ON DELETE CASCADE,
    review_type       varchar(50)  NOT NULL, -- 'TRANSCRIPT_REVIEW', 'SCRIPT_REVIEW', etc.
    status            varchar(20)  NOT NULL DEFAULT 'pending',
    original_payload  text         NOT NULL, -- raw output from previous step (JSON/text)
    reviewed_payload  text,                  -- user-corrected text/JSON from UI
    reviewed_by       uuid,                  -- user id who approved
    reviewed_at       timestamptz,
    created_at        timestamptz  NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX idx_review_task_run_id ON review_task (run_id);
CREATE INDEX idx_review_task_run_step_id ON review_task (run_step_id);
CREATE INDEX idx_review_task_status ON review_task (status);

-- media_asset: metadata catalog for binary artifacts generated across run steps
-- (downloaded raw video, separated audio, generated TTS voice, rendered MP4).
-- The binary files live on object storage (MinIO / S3); only paths/URLs and
-- metadata are tracked here.
CREATE TABLE media_asset (
    id                uuid         PRIMARY KEY,
    run_id            uuid         NOT NULL REFERENCES workflow_run (id) ON DELETE CASCADE,
    run_step_id       uuid         REFERENCES run_step (id) ON DELETE SET NULL,
    asset_type        varchar(50)  NOT NULL, -- 'RAW_VIDEO', 'AUDIO_VOCAL', 'AUDIO_BGM', 'TTS_AUDIO', 'FINAL_VIDEO'
    file_url          text         NOT NULL,
    storage_path      text         NOT NULL,
    file_size_bytes   bigint,
    mime_type         varchar(100),
    metadata          jsonb,                 -- duration, resolution, sample_rate, etc.
    created_at        timestamptz  NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX idx_media_asset_run_id ON media_asset (run_id);
CREATE INDEX idx_media_asset_run_step_id ON media_asset (run_step_id);
