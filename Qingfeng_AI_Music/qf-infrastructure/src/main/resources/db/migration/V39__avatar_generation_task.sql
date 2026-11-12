CREATE TABLE avatar_generation_task (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 creator_id VARCHAR(36) NOT NULL,
 request_key VARCHAR(36) NOT NULL,
 prompt VARCHAR(1000) NOT NULL,
 expected_version VARCHAR(36),
 status VARCHAR(24) NOT NULL,
 provider_task_id VARCHAR(255),
 lease_token VARCHAR(36),
 lease_until TIMESTAMP(6),
 next_attempt_at TIMESTAMP(6),
 failures INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_avatar_gen_request UNIQUE (creator_id, request_key),
 CONSTRAINT fk_avatar_gen_creator FOREIGN KEY (creator_id) REFERENCES creator(id)
);
CREATE INDEX idx_avatar_generation_scan ON avatar_generation_task (status, lease_until, updated_at);
