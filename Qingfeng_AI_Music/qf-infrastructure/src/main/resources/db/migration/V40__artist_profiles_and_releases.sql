CREATE TABLE artist_profile (
    creator_id VARCHAR(36) NOT NULL PRIMARY KEY,
    public_id VARCHAR(36) NOT NULL UNIQUE,
    stage_name VARCHAR(30) NOT NULL,
    tagline VARCHAR(120) NOT NULL,
    avatar_key VARCHAR(36) NULL,
    avatar_jpeg BLOB NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_artist_profile_creator FOREIGN KEY (creator_id) REFERENCES creator(id)
);

CREATE TABLE artist_song_release (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    creator_id VARCHAR(36) NOT NULL,
    work_id VARCHAR(36) NOT NULL,
    asset_id VARCHAR(36) NOT NULL,
    cover_id VARCHAR(36) NULL,
    title VARCHAR(100) NOT NULL,
    version BIGINT NOT NULL,
    show_on_profile BOOLEAN NOT NULL,
    published_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_artist_release_work UNIQUE (creator_id, work_id),
    CONSTRAINT fk_artist_release_profile FOREIGN KEY (creator_id) REFERENCES artist_profile(creator_id),
    CONSTRAINT fk_artist_release_work FOREIGN KEY (work_id) REFERENCES music_work(id),
    CONSTRAINT fk_artist_release_audio FOREIGN KEY (asset_id) REFERENCES media_asset(id),
    CONSTRAINT fk_artist_release_cover FOREIGN KEY (cover_id) REFERENCES cover_asset(id)
);
CREATE INDEX idx_artist_profile_releases ON artist_song_release(creator_id, show_on_profile, published_at);
