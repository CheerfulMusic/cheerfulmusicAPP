CREATE TABLE model_usage_lock (
    kind VARCHAR(16) NOT NULL PRIMARY KEY
);
INSERT INTO model_usage_lock(kind) VALUES ('MUSIC'), ('LYRICS');
CREATE TABLE model_usage_reservation (
    kind VARCHAR(16) NOT NULL,
    work_id VARCHAR(36) NOT NULL,
    request_key VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    usage_day DATE NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY(kind, work_id, request_key),
    CONSTRAINT fk_usage_work FOREIGN KEY(work_id) REFERENCES music_work(id),
    CONSTRAINT fk_usage_creator FOREIGN KEY(owner_id) REFERENCES creator(id)
);
CREATE INDEX idx_usage_day ON model_usage_reservation(kind, usage_day, owner_id);
ALTER TABLE lyrics_composition_request ADD COLUMN request_hash VARCHAR(64);
