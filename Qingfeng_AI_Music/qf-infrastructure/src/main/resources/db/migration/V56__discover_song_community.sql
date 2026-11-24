CREATE TABLE discover_song_description (
    release_id VARCHAR(36) NOT NULL,
    release_version BIGINT NOT NULL,
    body VARCHAR(500) NOT NULL,
    PRIMARY KEY (release_id, release_version)
);

CREATE TABLE discover_song_like (
    release_id VARCHAR(36) NOT NULL,
    release_version BIGINT NOT NULL,
    creator_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (release_id, release_version, creator_id)
);

CREATE TABLE discover_song_comment (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    release_id VARCHAR(36) NOT NULL,
    release_version BIGINT NOT NULL,
    creator_id VARCHAR(36) NOT NULL,
    body VARCHAR(400) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_discover_song_comments ON discover_song_comment (release_id, release_version, created_at, id);
