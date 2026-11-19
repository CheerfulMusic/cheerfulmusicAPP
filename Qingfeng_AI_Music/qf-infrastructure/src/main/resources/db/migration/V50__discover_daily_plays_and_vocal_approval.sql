CREATE TABLE discover_daily_play (
    play_day DATE NOT NULL,
    release_id VARCHAR(36) NOT NULL,
    release_version BIGINT NOT NULL,
    plays BIGINT NOT NULL,
    PRIMARY KEY (play_day, release_id, release_version)
);

CREATE TABLE discover_vocal_approval (
    release_id VARCHAR(36) NOT NULL,
    release_version BIGINT NOT NULL,
    PRIMARY KEY (release_id, release_version)
);
