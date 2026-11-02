CREATE TABLE lyrics_composition_request (
 work_id VARCHAR(36) NOT NULL,
 request_key VARCHAR(36) NOT NULL,
 status VARCHAR(16) NOT NULL,
 revision_id VARCHAR(36),
 created_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY(work_id,request_key),
 CONSTRAINT fk_composition_work FOREIGN KEY(work_id) REFERENCES music_work(id)
);
