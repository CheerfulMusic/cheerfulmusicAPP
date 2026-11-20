-- Match the release ID collation even if this database now has a different default.
SELECT character_set_name, collation_name INTO @qf_release_charset, @qf_release_collation
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'artist_song_release' AND column_name = 'id';

SET @qf_daily_play_ddl = CONCAT('CREATE TABLE discover_daily_play (
    play_day DATE NOT NULL,
    release_id VARCHAR(36) NOT NULL,
    release_version BIGINT NOT NULL,
    plays BIGINT NOT NULL,
    PRIMARY KEY (play_day, release_id, release_version)
) ENGINE=InnoDB DEFAULT CHARACTER SET ', @qf_release_charset, ' COLLATE ', @qf_release_collation);
PREPARE qf_discover_statement FROM @qf_daily_play_ddl;
EXECUTE qf_discover_statement;
DEALLOCATE PREPARE qf_discover_statement;

SET @qf_vocal_approval_ddl = CONCAT('CREATE TABLE discover_vocal_approval (
    release_id VARCHAR(36) NOT NULL,
    release_version BIGINT NOT NULL,
    PRIMARY KEY (release_id, release_version)
) ENGINE=InnoDB DEFAULT CHARACTER SET ', @qf_release_charset, ' COLLATE ', @qf_release_collation);
PREPARE qf_discover_statement FROM @qf_vocal_approval_ddl;
EXECUTE qf_discover_statement;
DEALLOCATE PREPARE qf_discover_statement;
