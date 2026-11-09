CREATE TABLE quick_creation (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 work_id VARCHAR(36) NOT NULL,
 work_version BIGINT NOT NULL,
 request_key VARCHAR(36) NOT NULL,
 language VARCHAR(12) NOT NULL,
 status VARCHAR(32) NOT NULL,
 lyrics_key VARCHAR(36) NOT NULL,
 music_key VARCHAR(36) NOT NULL,
 revision_id VARCHAR(36),
 task_id VARCHAR(36),
 error_code VARCHAR(80),
 lease_token VARCHAR(36),
 lease_until TIMESTAMP(6),
 next_attempt_at TIMESTAMP(6) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_quick_work FOREIGN KEY(work_id) REFERENCES music_work(id),
 CONSTRAINT uk_quick_request UNIQUE(work_id,request_key)
);
CREATE INDEX idx_quick_due ON quick_creation(status,next_attempt_at,lease_until);
CREATE INDEX idx_quick_work ON quick_creation(work_id,created_at);
