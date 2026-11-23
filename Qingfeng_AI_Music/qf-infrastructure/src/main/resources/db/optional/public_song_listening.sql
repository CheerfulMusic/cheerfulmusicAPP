-- Public listening, independent of the payment schema target. Apply before publishing the listening features.
CREATE TABLE IF NOT EXISTS listening_story (
    share_id VARCHAR(36) PRIMARY KEY,
    body VARCHAR(1500) NOT NULL,
    FOREIGN KEY (share_id) REFERENCES share_link(id)
);
CREATE TABLE IF NOT EXISTS listening_reaction (
    share_id VARCHAR(36) NOT NULL,
    kind VARCHAR(12) NOT NULL,
    creator_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (share_id, kind, creator_id),
    FOREIGN KEY (share_id) REFERENCES share_link(id),
    FOREIGN KEY (creator_id) REFERENCES creator(id)
);
CREATE TABLE IF NOT EXISTS listening_comment (
    id VARCHAR(36) PRIMARY KEY,
    share_id VARCHAR(36) NOT NULL,
    creator_id VARCHAR(36) NOT NULL,
    body VARCHAR(400) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    INDEX ix_listening_comments (share_id, created_at, id),
    FOREIGN KEY (share_id) REFERENCES share_link(id),
    FOREIGN KEY (creator_id) REFERENCES creator(id)
);
CREATE TABLE IF NOT EXISTS listening_comment_like (
    comment_id VARCHAR(36) NOT NULL,
    creator_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (comment_id,creator_id),
    FOREIGN KEY (comment_id) REFERENCES listening_comment(id) ON DELETE CASCADE,
    FOREIGN KEY (creator_id) REFERENCES creator(id)
);
CREATE TABLE IF NOT EXISTS listening_artist_follow (
    artist_id VARCHAR(36) NOT NULL,
    creator_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (artist_id,creator_id),
    FOREIGN KEY (artist_id) REFERENCES artist_profile(public_id),
    FOREIGN KEY (creator_id) REFERENCES creator(id)
);
CREATE TABLE IF NOT EXISTS listening_album (
    id VARCHAR(36) PRIMARY KEY,
    creator_id VARCHAR(36) NOT NULL,
    title VARCHAR(100) NOT NULL,
    description VARCHAR(1200) NOT NULL,
    published BOOLEAN NOT NULL,
    version BIGINT NOT NULL,
    cover_key VARCHAR(36),
    cover_jpeg MEDIUMBLOB,
    updated_at TIMESTAMP(6) NOT NULL,
    INDEX ix_listening_album_owner (creator_id, published, updated_at),
    FOREIGN KEY (creator_id) REFERENCES creator(id)
);
CREATE TABLE IF NOT EXISTS listening_album_track (
    album_id VARCHAR(36) NOT NULL,
    release_id VARCHAR(36) NOT NULL,
    asset_id VARCHAR(36) NOT NULL,
    track_no INT NOT NULL,
    PRIMARY KEY (album_id, release_id),
    UNIQUE (album_id, track_no),
    FOREIGN KEY (album_id) REFERENCES listening_album(id) ON DELETE CASCADE,
    FOREIGN KEY (release_id) REFERENCES artist_song_release(id) ON DELETE CASCADE
);
