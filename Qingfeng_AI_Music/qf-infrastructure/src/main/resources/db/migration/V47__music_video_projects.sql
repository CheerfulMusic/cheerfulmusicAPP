-- Durable MV projects. Provider submits are never retried without an explicit new request.
CREATE TABLE music_video_project (
 id VARCHAR(36) PRIMARY KEY,
 creator_id VARCHAR(36) NOT NULL,
 work_id VARCHAR(36) NOT NULL,
 asset_id VARCHAR(36) NOT NULL,
 request_key VARCHAR(100) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL,
 state VARCHAR(32) NOT NULL,
 plan_json LONGTEXT NOT NULL,
 error_code VARCHAR(80),
 lease_until TIMESTAMP NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uq_music_video_request(creator_id,request_key),
 FOREIGN KEY (creator_id) REFERENCES creator(id),
 INDEX ix_music_video_queue(state,lease_until)
);
CREATE TABLE music_video_shot (
 id VARCHAR(36) PRIMARY KEY,
 project_id VARCHAR(36) NOT NULL,
 shot_index INT NOT NULL,
 prompt TEXT NOT NULL,
 seconds INT NOT NULL,
 state VARCHAR(32) NOT NULL,
 provider_task_id VARCHAR(200),
 error_code VARCHAR(80),
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uq_music_video_shot(project_id,shot_index),
 FOREIGN KEY (project_id) REFERENCES music_video_project(id)
);
