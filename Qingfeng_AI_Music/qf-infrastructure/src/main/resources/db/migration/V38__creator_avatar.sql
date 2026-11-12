CREATE TABLE creator_avatar (
    creator_id VARCHAR(36) NOT NULL PRIMARY KEY,
    storage_key VARCHAR(512) NOT NULL,
    byte_size BIGINT NOT NULL,
    version VARCHAR(36) NOT NULL,
    CONSTRAINT fk_avatar_creator FOREIGN KEY (creator_id) REFERENCES creator(id)
);
