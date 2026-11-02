CREATE TABLE creator (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    created_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE creator_session (
    token_hash VARCHAR(64) NOT NULL PRIMARY KEY,
    creator_id VARCHAR(36) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_session_creator FOREIGN KEY (creator_id) REFERENCES creator(id)
);
CREATE INDEX idx_session_expiry ON creator_session(expires_at);
CREATE TABLE music_work (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    recipient VARCHAR(80) NOT NULL,
    occasion VARCHAR(32) NOT NULL,
    story TEXT NOT NULL,
    message VARCHAR(500) NOT NULL,
    style VARCHAR(80) NOT NULL,
    title VARCHAR(100) NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_work_owner FOREIGN KEY (owner_id) REFERENCES creator(id)
);
CREATE INDEX idx_work_owner_updated ON music_work(owner_id, updated_at);
