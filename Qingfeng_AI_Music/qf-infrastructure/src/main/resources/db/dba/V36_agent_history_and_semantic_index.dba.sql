-- Forward-only DBA copy; execute once only after version check and verified backup.
-- Do not run alongside Flyway for the same version. See docs/concurrency-reliability.md for writer cutover.
-- Do not run this script from local daily startup or against production for automated tests.
-- Forward-only. Applying a migration does not contact providers or backfill historical works.
ALTER TABLE agent_conversation ADD COLUMN history_epoch BIGINT NOT NULL DEFAULT 0;

CREATE TABLE agent_semantic_index_job (
    work_id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    requested_version BIGINT NOT NULL DEFAULT 1,
    completed_version BIGINT NOT NULL DEFAULT 0,
    attempts INT NOT NULL DEFAULT 0,
    available_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_token VARCHAR(36),
    lease_until TIMESTAMP(6),
    last_error VARCHAR(80),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_semantic_ready (available_at,lease_until),
    INDEX idx_semantic_owner (owner_id,work_id)
);
