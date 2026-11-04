ALTER TABLE lyrics_composition_request ADD COLUMN error_code VARCHAR(64);
ALTER TABLE lyrics_composition_request ADD COLUMN finished_at TIMESTAMP(6);
CREATE INDEX idx_composition_status_created ON lyrics_composition_request(status, created_at);

-- 旧 UNKNOWN 仅由同步歌词调用的异常出口写入：本地请求已结束，保留计费记录和幂等键。
UPDATE lyrics_composition_request
SET status='FAILED', error_code='LYRICS_LEGACY_RESULT_UNKNOWN', finished_at=CURRENT_TIMESTAMP
WHERE status='UNKNOWN';
