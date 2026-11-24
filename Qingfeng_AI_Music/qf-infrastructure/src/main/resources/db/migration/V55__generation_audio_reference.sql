ALTER TABLE music_work ADD COLUMN reference_track_id VARCHAR(64) /*!80012 , ALGORITHM=INSTANT */;

CREATE TABLE generation_audio_reference (
    task_id VARCHAR(36) NOT NULL PRIMARY KEY,
    object_key VARCHAR(255) NOT NULL,
    audio_weight DECIMAL(3,2) NOT NULL
);

-- The older task ID column may use a different collation from the database default.
/*!80012 SET @qf_audio_reference_ddl = (
    SELECT CONCAT('ALTER TABLE generation_audio_reference MODIFY COLUMN task_id VARCHAR(36) CHARACTER SET ',
                  CHARACTER_SET_NAME, ' COLLATE ', COLLATION_NAME, ' NOT NULL')
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'generation_task' AND COLUMN_NAME = 'id'
) */;
/*!80012 PREPARE qf_audio_reference_stmt FROM @qf_audio_reference_ddl */;
/*!80012 EXECUTE qf_audio_reference_stmt */;
/*!80012 DEALLOCATE PREPARE qf_audio_reference_stmt */;
/*!80012 SET @qf_audio_reference_ddl = NULL */;

ALTER TABLE generation_audio_reference
    ADD CONSTRAINT fk_generation_audio_reference_task FOREIGN KEY(task_id) REFERENCES generation_task(id);
