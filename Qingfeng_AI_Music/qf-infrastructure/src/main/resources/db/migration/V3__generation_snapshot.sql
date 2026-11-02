CREATE TABLE generation_task (
 id VARCHAR(36) NOT NULL PRIMARY KEY, work_id VARCHAR(36) NOT NULL,
 revision_id VARCHAR(36) NOT NULL, work_version BIGINT NOT NULL,
 request_key VARCHAR(36) NOT NULL, title VARCHAR(100) NOT NULL, lyrics TEXT NOT NULL,
 style VARCHAR(80) NOT NULL, provider VARCHAR(80) NOT NULL,
 status VARCHAR(32) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_generation_request UNIQUE(work_id,request_key),
 CONSTRAINT fk_task_work FOREIGN KEY(work_id) REFERENCES music_work(id),
 CONSTRAINT fk_task_lyric FOREIGN KEY(revision_id) REFERENCES lyric_revision(id)
);
CREATE INDEX idx_task_work ON generation_task(work_id,created_at);
