CREATE TABLE cover_asset (
 id VARCHAR(36) NOT NULL PRIMARY KEY,work_id VARCHAR(36) NOT NULL,
 storage_key VARCHAR(180) NOT NULL,byte_size BIGINT NOT NULL,origin VARCHAR(16) NOT NULL,
 edit_task_id VARCHAR(36) UNIQUE,created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_cover_work FOREIGN KEY(work_id) REFERENCES music_work(id)
);
CREATE INDEX idx_cover_work ON cover_asset(work_id,created_at);
CREATE TABLE work_cover (
 work_id VARCHAR(36) NOT NULL PRIMARY KEY,cover_id VARCHAR(36),version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT fk_work_cover_work FOREIGN KEY(work_id) REFERENCES music_work(id),
 CONSTRAINT fk_work_cover_asset FOREIGN KEY(cover_id) REFERENCES cover_asset(id)
);
ALTER TABLE share_link ADD COLUMN cover_id VARCHAR(36);
CREATE TABLE cover_edit_task (
 id VARCHAR(36) NOT NULL PRIMARY KEY,work_id VARCHAR(36) NOT NULL,source_id VARCHAR(36) NOT NULL,
 request_key VARCHAR(36) NOT NULL,prompt VARCHAR(1200) NOT NULL,model VARCHAR(80) NOT NULL,
 status VARCHAR(32) NOT NULL,provider_task_id VARCHAR(255),result_url TEXT,cover_id VARCHAR(36),
 error_code VARCHAR(64),created_at TIMESTAMP(6) NOT NULL,updated_at TIMESTAMP(6) NOT NULL,
 next_attempt_at TIMESTAMP(6) NOT NULL,lease_token VARCHAR(36),lease_until TIMESTAMP(6),
 CONSTRAINT uq_cover_request UNIQUE(work_id,request_key),
 CONSTRAINT fk_cover_edit_work FOREIGN KEY(work_id) REFERENCES music_work(id),
 CONSTRAINT fk_cover_edit_source FOREIGN KEY(source_id) REFERENCES cover_asset(id)
);
CREATE INDEX idx_cover_edit_scan ON cover_edit_task(status,next_attempt_at,lease_until);
INSERT INTO model_usage_lock(kind) VALUES('COVER');
