CREATE TABLE wechat_card_binding (
    creator_id VARCHAR(36) NOT NULL,
    app_id VARCHAR(32) NOT NULL,
    open_id VARBINARY(128),
    code_hash VARCHAR(64),
    code_expires_at BIGINT,
    last_message_at BIGINT,
    blocked_message_at BIGINT,
    PRIMARY KEY (creator_id, app_id),
    CONSTRAINT uq_wechat_card_open_id UNIQUE (app_id, open_id),
    CONSTRAINT uq_wechat_card_code UNIQUE (app_id, code_hash)
);

CREATE TABLE wechat_card_delivery (
    creator_id VARCHAR(36) NOT NULL,
    app_id VARCHAR(32) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    song_hash VARCHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL,
    requested_at BIGINT NOT NULL,
    PRIMARY KEY (creator_id, app_id, request_id)
);
