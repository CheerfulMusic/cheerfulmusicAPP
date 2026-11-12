-- TEST ONLY. Run after the unchanged canonical core migrations through V39 on H2.
-- This compatibility fixture does not execute or certify MySQL V40, its triggers,
-- protocol, permissions, financial model, or activation. Existing rows are retained.
ALTER TABLE payment_webhook_inbox ADD COLUMN merchant_scope VARCHAR(128) NOT NULL DEFAULT 'LEGACY_UNKNOWN';
ALTER TABLE payment_webhook_inbox ADD COLUMN env_scope VARCHAR(16) NOT NULL DEFAULT 'LEGACY_UNKNOWN';
ALTER TABLE payment_webhook_inbox DROP PRIMARY KEY;
DROP INDEX idx_payment_inbox_due;
ALTER TABLE payment_webhook_inbox ADD PRIMARY KEY(provider,merchant_scope,env_scope,event_id);
CREATE INDEX idx_payment_inbox_due ON payment_webhook_inbox(provider,merchant_scope,env_scope,status,next_attempt_at,lease_until);

CREATE TABLE payment_inbox_review (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  provider VARCHAR(24) NOT NULL,
  merchant_scope VARCHAR(128) NOT NULL,
  env_scope VARCHAR(16) NOT NULL,
  event_id VARCHAR(128) NOT NULL,
  reviewer VARCHAR(128) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  action VARCHAR(24) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT fk_payment_inbox_review_event FOREIGN KEY(provider,merchant_scope,env_scope,event_id)
    REFERENCES payment_webhook_inbox(provider,merchant_scope,env_scope,event_id),
  CONSTRAINT chk_payment_inbox_review_action CHECK(action='RESUME')
);
CREATE INDEX idx_payment_review_scope_created ON payment_inbox_review(provider,merchant_scope,env_scope,event_id,created_at);

CREATE TABLE payment_inbox_attempt (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  provider VARCHAR(24) NOT NULL,
  merchant_scope VARCHAR(128) NOT NULL,
  env_scope VARCHAR(16) NOT NULL,
  event_id VARCHAR(128) NOT NULL,
  attempt INTEGER NOT NULL,
  result VARCHAR(24) NOT NULL,
  evidence_id VARCHAR(512),
  created_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT fk_payment_inbox_attempt_event FOREIGN KEY(provider,merchant_scope,env_scope,event_id)
    REFERENCES payment_webhook_inbox(provider,merchant_scope,env_scope,event_id),
  CONSTRAINT chk_payment_inbox_attempt_number CHECK(attempt>=0),
  CONSTRAINT chk_payment_inbox_attempt_result CHECK(result IN('WAITING','DONE','FAILED','MANUAL'))
);
CREATE INDEX idx_payment_attempt_scope_created ON payment_inbox_attempt(provider,merchant_scope,env_scope,event_id,created_at);
