CREATE TABLE media_lyrics_timeline (
    asset_id VARCHAR(36) NOT NULL PRIMARY KEY,
    work_id VARCHAR(36) NOT NULL,
    state VARCHAR(24) NOT NULL,
    request_key VARCHAR(36),
    words_json MEDIUMTEXT,
    attempts INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_timeline_asset FOREIGN KEY(asset_id) REFERENCES media_asset(id),
    CONSTRAINT fk_timeline_work FOREIGN KEY(work_id) REFERENCES music_work(id)
);
CREATE INDEX idx_timeline_state ON media_lyrics_timeline(state,work_id);
INSERT INTO model_usage_lock(kind) VALUES ('TIMING');
