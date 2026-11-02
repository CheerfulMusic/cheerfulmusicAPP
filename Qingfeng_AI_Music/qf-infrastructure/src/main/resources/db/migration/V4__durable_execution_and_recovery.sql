ALTER TABLE generation_task ADD COLUMN provider_task_id VARCHAR(255);
ALTER TABLE generation_task ADD COLUMN audio_url TEXT;
ALTER TABLE generation_task ADD COLUMN asset_id VARCHAR(36);
ALTER TABLE generation_task ADD COLUMN lease_token VARCHAR(36);
ALTER TABLE generation_task ADD COLUMN lease_until TIMESTAMP(6);
ALTER TABLE generation_task ADD COLUMN next_attempt_at TIMESTAMP(6);
ALTER TABLE generation_task ADD COLUMN submit_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE generation_task ADD COLUMN archive_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE generation_task ADD COLUMN query_failures INT NOT NULL DEFAULT 0;
ALTER TABLE generation_task ADD COLUMN last_error_code VARCHAR(64);
ALTER TABLE generation_task ADD COLUMN updated_at TIMESTAMP(6);
UPDATE generation_task SET next_attempt_at=created_at, updated_at=created_at;
CREATE INDEX idx_task_scan ON generation_task(provider,status,next_attempt_at,lease_until);

CREATE TABLE generation_worker_lock (
 provider VARCHAR(80) NOT NULL PRIMARY KEY,
 lease_token VARCHAR(36), lease_until TIMESTAMP(6)
);
CREATE TABLE generation_attempt (
 id VARCHAR(36) NOT NULL PRIMARY KEY, task_id VARCHAR(36) NOT NULL,
 phase VARCHAR(20) NOT NULL, outcome VARCHAR(24) NOT NULL,
 error_code VARCHAR(64), started_at TIMESTAMP(6) NOT NULL, ended_at TIMESTAMP(6),
 CONSTRAINT fk_attempt_task FOREIGN KEY(task_id) REFERENCES generation_task(id)
);
CREATE INDEX idx_attempt_task ON generation_attempt(task_id,started_at);
ALTER TABLE media_asset ADD COLUMN generation_task_id VARCHAR(36);
CREATE UNIQUE INDEX uq_media_generation ON media_asset(generation_task_id);

ALTER TABLE creator ADD COLUMN recovery_hash VARCHAR(64);
CREATE UNIQUE INDEX uq_creator_recovery ON creator(recovery_hash);
