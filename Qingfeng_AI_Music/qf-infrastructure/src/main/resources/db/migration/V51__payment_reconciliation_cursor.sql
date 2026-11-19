CREATE TABLE payment_reconciliation_cursor (
    provider VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    merchant_scope VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    env_scope VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stream VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    window_start BIGINT NOT NULL,
    window_end BIGINT NOT NULL,
    page_token VARCHAR(2048) NOT NULL,
    retention_gap_start BIGINT NULL,
    retention_gap_end BIGINT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY(provider,merchant_scope,env_scope,stream)
) ENGINE=InnoDB;
