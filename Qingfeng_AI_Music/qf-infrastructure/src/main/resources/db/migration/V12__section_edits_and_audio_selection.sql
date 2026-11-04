CREATE TABLE generation_audio_source (
 task_id VARCHAR(36) NOT NULL PRIMARY KEY,
 audio_id VARCHAR(255) NOT NULL,
 duration_s DECIMAL(12,2) NOT NULL,
 audio_url VARCHAR(4096) NOT NULL,
 CONSTRAINT fk_audio_source_task FOREIGN KEY(task_id) REFERENCES generation_task(id)
);
CREATE TABLE section_edit (
 task_id VARCHAR(36) NOT NULL PRIMARY KEY,
 source_asset_id VARCHAR(36) NOT NULL,
 source_provider_task_id VARCHAR(255) NOT NULL,
 source_provider_audio_id VARCHAR(255) NOT NULL,
 start_s DECIMAL(12,2) NOT NULL,
 end_s DECIMAL(12,2) NOT NULL,
 source_duration_s DECIMAL(12,2) NOT NULL,
 text_start INT NOT NULL,
 text_end INT NOT NULL,
 replacement_lyrics TEXT NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 CONSTRAINT fk_edit_task FOREIGN KEY(task_id) REFERENCES generation_task(id),
 CONSTRAINT fk_edit_source FOREIGN KEY(source_asset_id) REFERENCES media_asset(id)
);
CREATE TABLE work_audio_selection (
 work_id VARCHAR(36) NOT NULL PRIMARY KEY,
 asset_id VARCHAR(36),
 version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT fk_audio_choice_work FOREIGN KEY(work_id) REFERENCES music_work(id),
 CONSTRAINT fk_audio_choice_asset FOREIGN KEY(asset_id) REFERENCES media_asset(id)
);
