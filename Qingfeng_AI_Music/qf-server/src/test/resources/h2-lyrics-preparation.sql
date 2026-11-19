-- H2 fixture for lyrics preparation. MySQL V49 separately matches existing identifier collations.
CREATE TABLE lyrics_preparation_job (
 source_key VARCHAR(160) NOT NULL PRIMARY KEY,
 asset_id VARCHAR(36) NULL,
 work_id VARCHAR(36) NULL,
 sample_id VARCHAR(64) NULL,
 audio_url TEXT NOT NULL,
 original_task_id VARCHAR(255) NULL,
 mode VARCHAR(24) NOT NULL,
 state VARCHAR(24) NOT NULL,
 request_key VARCHAR(36) NOT NULL,
 external_task_id VARCHAR(255) NULL,
 result_json MEDIUMTEXT NULL,
 attempts INT NOT NULL DEFAULT 0,
 poll_failures INT NOT NULL DEFAULT 0,
 lease_token VARCHAR(36) NULL,
 lease_until TIMESTAMP(6) NULL,
 next_attempt_at TIMESTAMP(6) NOT NULL,
 last_submit_at TIMESTAMP(6) NULL,
 error_code VARCHAR(80) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT fk_preparation_asset FOREIGN KEY(asset_id) REFERENCES media_asset(id),
 CONSTRAINT fk_preparation_work FOREIGN KEY(work_id) REFERENCES music_work(id)
);
CREATE INDEX ix_lyrics_preparation_queue ON lyrics_preparation_job(state,next_attempt_at);

CREATE TABLE lyrics_preparation_control (
 control_key VARCHAR(40) NOT NULL PRIMARY KEY,
 started_at TIMESTAMP(6) NOT NULL
);
