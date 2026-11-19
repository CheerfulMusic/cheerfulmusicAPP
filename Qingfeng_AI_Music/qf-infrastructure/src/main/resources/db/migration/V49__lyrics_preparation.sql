-- Legacy tables can use different collations from the database and from each other.
SET @qf_asset_charset = (SELECT character_set_name FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'media_asset' AND column_name = 'id');
SET @qf_asset_collation = (SELECT collation_name FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'media_asset' AND column_name = 'id');
SET @qf_work_charset = (SELECT character_set_name FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'music_work' AND column_name = 'id');
SET @qf_work_collation = (SELECT collation_name FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'music_work' AND column_name = 'id');

SET @qf_lyrics_job_ddl = CONCAT('CREATE TABLE lyrics_preparation_job (
 source_key VARCHAR(160) NOT NULL PRIMARY KEY,
 asset_id VARCHAR(36) NULL,
 work_id VARCHAR(36) CHARACTER SET ', @qf_work_charset, ' COLLATE ', @qf_work_collation, ' NULL,
 sample_id VARCHAR(64) NULL,
 audio_url TEXT NOT NULL,
 original_task_id VARCHAR(255) NULL,
 mode VARCHAR(24) NOT NULL,
 state VARCHAR(24) NOT NULL,
 request_key VARCHAR(36) NOT NULL,
 external_task_id VARCHAR(255) NULL,
 result_json MEDIUMTEXT NULL,
 attempts INT NOT NULL DEFAULT 0,
 poll_failures INT NOT NULL DEFAULT 0,
 lease_token VARCHAR(36) NULL,
 lease_until TIMESTAMP(6) NULL,
 next_attempt_at TIMESTAMP(6) NOT NULL,
 last_submit_at TIMESTAMP(6) NULL,
 error_code VARCHAR(80) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_preparation_asset FOREIGN KEY(asset_id) REFERENCES media_asset(id),
 CONSTRAINT fk_preparation_work FOREIGN KEY(work_id) REFERENCES music_work(id),
 INDEX ix_lyrics_preparation_queue (state,next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARACTER SET ', @qf_asset_charset, ' COLLATE ', @qf_asset_collation);
PREPARE qf_lyrics_job_statement FROM @qf_lyrics_job_ddl;
EXECUTE qf_lyrics_job_statement;
DEALLOCATE PREPARE qf_lyrics_job_statement;

CREATE TABLE lyrics_preparation_control (
 control_key VARCHAR(40) NOT NULL PRIMARY KEY,
 started_at TIMESTAMP(6) NOT NULL
);
