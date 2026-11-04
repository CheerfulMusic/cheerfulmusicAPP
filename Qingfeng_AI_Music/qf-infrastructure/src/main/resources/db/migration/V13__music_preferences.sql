CREATE TABLE work_music_preferences (
 work_id VARCHAR(36) NOT NULL PRIMARY KEY,
 settings_json MEDIUMTEXT NOT NULL,
 CONSTRAINT fk_music_preferences_work FOREIGN KEY(work_id) REFERENCES music_work(id)
);
CREATE TABLE generation_music_preferences (
 task_id VARCHAR(36) NOT NULL PRIMARY KEY,
 snapshot_json MEDIUMTEXT NOT NULL,
 CONSTRAINT fk_music_preferences_task FOREIGN KEY(task_id) REFERENCES generation_task(id)
);
