CREATE TABLE lyric_revision (
 id VARCHAR(36) NOT NULL PRIMARY KEY, work_id VARCHAR(36) NOT NULL,
 work_version BIGINT NOT NULL, title VARCHAR(100) NOT NULL, lyrics TEXT NOT NULL,
 source VARCHAR(24) NOT NULL, confirmed BOOLEAN NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_lyric_work FOREIGN KEY(work_id) REFERENCES music_work(id)
);
CREATE INDEX idx_lyric_work ON lyric_revision(work_id,created_at);
CREATE TABLE media_asset (
 id VARCHAR(36) NOT NULL PRIMARY KEY, work_id VARCHAR(36) NOT NULL,
 storage_key VARCHAR(120) NOT NULL, origin VARCHAR(24) NOT NULL,
 content_type VARCHAR(80) NOT NULL, byte_size BIGINT NOT NULL,
 sha256 VARCHAR(64) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_media_work FOREIGN KEY(work_id) REFERENCES music_work(id)
);
CREATE INDEX idx_media_work ON media_asset(work_id,created_at);
CREATE TABLE share_link (
 id VARCHAR(36) NOT NULL PRIMARY KEY, work_id VARCHAR(36) NOT NULL,
 token_hash VARCHAR(64) NOT NULL UNIQUE, asset_id VARCHAR(36) NOT NULL,
 title VARCHAR(100) NOT NULL, recipient VARCHAR(80) NOT NULL,
 message VARCHAR(500) NOT NULL, lyrics TEXT NOT NULL, audio_origin VARCHAR(24) NOT NULL,
 active BOOLEAN NOT NULL, created_at TIMESTAMP(6) NOT NULL, expires_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_share_work FOREIGN KEY(work_id) REFERENCES music_work(id),
 CONSTRAINT fk_share_asset FOREIGN KEY(asset_id) REFERENCES media_asset(id)
);
CREATE INDEX idx_share_work ON share_link(work_id,active);
