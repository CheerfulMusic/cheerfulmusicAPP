ALTER TABLE artist_song_release ADD COLUMN discover_published_at TIMESTAMP(6) NULL;
ALTER TABLE artist_song_release ADD COLUMN discover_featured BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_artist_release_discover_latest ON artist_song_release(discover_published_at, id);
CREATE INDEX idx_artist_release_discover_featured ON artist_song_release(discover_featured, discover_published_at, id);
