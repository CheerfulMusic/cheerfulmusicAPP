-- Reference schema ONLY. Not a Flyway migration; never run against the main DB.
-- Uses MySQL 8.0 / InnoDB (default engine must be InnoDB).
CREATE TABLE demo_owner (id VARCHAR(36) PRIMARY KEY);
CREATE TABLE demo_payment_order (
 id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(36) NOT NULL,
 amount_minor BIGINT NOT NULL, currency VARCHAR(3) NOT NULL, credits BIGINT NOT NULL,
 provider VARCHAR(24) NOT NULL, merchant VARCHAR(128) NOT NULL, environment VARCHAR(16) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 FOREIGN KEY(owner_id) REFERENCES demo_owner(id),
 CHECK (amount_minor>0 AND credits>0)
);
CREATE TABLE demo_credit_outbox (
 order_id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(36) NOT NULL,
 credits BIGINT NOT NULL, provider VARCHAR(24) NOT NULL,
 merchant VARCHAR(128) NOT NULL, environment VARCHAR(16) NOT NULL,
 capture_id VARCHAR(128) NOT NULL,
 state VARCHAR(16) NOT NULL, attempts INT NOT NULL DEFAULT 0,
 next_attempt_at TIMESTAMP(6) NOT NULL, lease_token VARCHAR(36), lease_until TIMESTAMP(6),
 last_error VARCHAR(80), created_at TIMESTAMP(6) NOT NULL, completed_at TIMESTAMP(6),
 FOREIGN KEY(order_id) REFERENCES demo_payment_order(id),
 UNIQUE(provider,merchant,environment,capture_id),
 CHECK (state='READY' OR state='PROCESSING' OR state='DONE' OR state='DEAD'),
 CHECK (credits>0 AND attempts>=0)
);
CREATE INDEX idx_demo_credit_due ON demo_credit_outbox(state,next_attempt_at,created_at);
CREATE INDEX idx_demo_credit_lease ON demo_credit_outbox(state,lease_until);
CREATE TABLE demo_credit_alert (
 order_id VARCHAR(36) PRIMARY KEY, reason VARCHAR(80) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL, acknowledged_at TIMESTAMP(6),
 FOREIGN KEY(order_id) REFERENCES demo_credit_outbox(order_id)
);
