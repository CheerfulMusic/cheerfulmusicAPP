ALTER TABLE music_work ADD COLUMN reference_track_id VARCHAR(64);

CREATE TABLE generation_audio_reference (
    task_id VARCHAR(36) NOT NULL PRIMARY KEY,
    object_key VARCHAR(255) NOT NULL,
    audio_weight DECIMAL(3,2) NOT NULL,
    CONSTRAINT fk_generation_audio_reference_task FOREIGN KEY(task_id) REFERENCES generation_task(id)
);
