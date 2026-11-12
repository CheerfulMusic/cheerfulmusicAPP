CREATE TABLE task_event_outbox (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    topic VARCHAR(80) NOT NULL,
    event_key VARCHAR(160) NOT NULL,
    payload TEXT NOT NULL,
    state VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    lease_token VARCHAR(36),
    lease_until TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6),
    last_error VARCHAR(80),
    CONSTRAINT uq_task_event UNIQUE(topic,event_key),
    INDEX idx_task_event_ready (state,next_attempt_at,created_at),
    INDEX idx_task_event_lease (state,lease_until)
);
