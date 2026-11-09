ALTER TABLE music_work ADD COLUMN deleted_at TIMESTAMP(6) NULL;
CREATE INDEX idx_work_owner_deleted_updated ON music_work(owner_id,deleted_at,updated_at,id);
