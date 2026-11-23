ALTER TABLE share_link ADD COLUMN public_song_id VARCHAR(43) NULL;
ALTER TABLE share_link ADD COLUMN page_views BIGINT NOT NULL DEFAULT 0;
ALTER TABLE share_link ADD COLUMN playback_starts BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_share_song_active ON share_link(public_song_id,active,created_at);
