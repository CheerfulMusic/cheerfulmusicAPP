ALTER TABLE creator ADD COLUMN media_bytes BIGINT NOT NULL DEFAULT 0;
UPDATE creator SET media_bytes=COALESCE((SELECT SUM(m.byte_size) FROM media_asset m JOIN music_work w ON m.work_id=w.id WHERE w.owner_id=creator.id),0);
