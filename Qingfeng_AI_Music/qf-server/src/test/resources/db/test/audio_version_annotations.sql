-- Portable test fixture. The optional MySQL release script uses dynamic collation
-- metadata and is verified separately against an isolated real MySQL instance.
CREATE TABLE IF NOT EXISTS media_asset_annotation (
 asset_id VARCHAR(36) NOT NULL PRIMARY KEY,
 note VARCHAR(500) NOT NULL DEFAULT '',
 note_version BIGINT NOT NULL DEFAULT 0,
 deleted_at TIMESTAMP(6) NULL
);
