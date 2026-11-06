CREATE TABLE lyric_creative_plan (
    revision_id VARCHAR(36) PRIMARY KEY,
    work_id VARCHAR(36) NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    plan_json TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_creative_plan_revision FOREIGN KEY (revision_id) REFERENCES lyric_revision(id),
    CONSTRAINT fk_creative_plan_work FOREIGN KEY (work_id) REFERENCES music_work(id)
);
CREATE INDEX idx_creative_plan_work ON lyric_creative_plan(work_id,source_hash,created_at);

ALTER TABLE media_asset ADD COLUMN candidate_key VARCHAR(64) NOT NULL DEFAULT 'primary';
ALTER TABLE media_asset ADD COLUMN provider_audio_id VARCHAR(255);
ALTER TABLE media_asset ADD COLUMN source_audio_url VARCHAR(4096);
ALTER TABLE media_asset ADD COLUMN duration_s DOUBLE;
DROP INDEX uq_media_generation ON media_asset;
CREATE UNIQUE INDEX uq_media_generation_candidate ON media_asset(generation_task_id,candidate_key);

CREATE TABLE generation_audio_candidates (
    task_id VARCHAR(36) PRIMARY KEY,
    candidates_json TEXT NOT NULL,
    CONSTRAINT fk_audio_candidates_task FOREIGN KEY (task_id) REFERENCES generation_task(id)
);

CREATE TABLE music_listening_feedback (
    asset_id VARCHAR(36) PRIMARY KEY,
    feedback VARCHAR(32) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_listening_feedback_asset FOREIGN KEY (asset_id) REFERENCES media_asset(id)
);
