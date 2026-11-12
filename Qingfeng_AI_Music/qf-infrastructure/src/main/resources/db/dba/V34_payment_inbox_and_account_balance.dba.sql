-- Forward-only DBA copy; execute once only after version check and verified backup.
-- Do not run alongside Flyway for the same version. See docs/concurrency-reliability.md for writer cutover.
-- Do not run this script from local daily startup or against production for automated tests.
-- Forward-only: account balances are initialized from the immutable ledger under the creator lock.
ALTER TABLE creator ADD COLUMN payment_risk_frozen INTEGER NOT NULL DEFAULT 0;
CREATE TABLE credit_account (
  owner_id VARCHAR(36) NOT NULL PRIMARY KEY,
  balance BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT fk_credit_account_owner FOREIGN KEY(owner_id) REFERENCES creator(id)
);
CREATE TABLE rewrite_admission_lock (id INTEGER PRIMARY KEY);
INSERT INTO rewrite_admission_lock(id) VALUES(1);

CREATE TABLE payment_webhook_inbox (
  provider VARCHAR(24) NOT NULL,
  event_id VARCHAR(128) NOT NULL,
  event_type VARCHAR(96) NOT NULL,
  payload TEXT NOT NULL,
  payload_hash VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP(6) NOT NULL,
  lease_token VARCHAR(36),
  lease_until TIMESTAMP(6),
  last_error VARCHAR(80),
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY(provider,event_id)
);
CREATE INDEX idx_payment_inbox_due ON payment_webhook_inbox(status,next_attempt_at,lease_until);

CREATE TABLE payment_risk_case (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  provider VARCHAR(24) NOT NULL,
  resource_id VARCHAR(128) NOT NULL,
  event_type VARCHAR(96) NOT NULL,
  order_id VARCHAR(36) NOT NULL,
  owner_id VARCHAR(36) NOT NULL,
  status VARCHAR(24) NOT NULL,
  last_event_id VARCHAR(128) NOT NULL,
  decision VARCHAR(40),
  reviewed_by VARCHAR(128),
  review_reason VARCHAR(1000),
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT uq_payment_risk_resource UNIQUE(provider,resource_id,order_id),
  CONSTRAINT fk_payment_risk_order FOREIGN KEY(order_id) REFERENCES payment_order(id),
  CONSTRAINT fk_payment_risk_owner FOREIGN KEY(owner_id) REFERENCES creator(id)
);
CREATE INDEX idx_payment_risk_owner_status ON payment_risk_case(owner_id,status);
CREATE TABLE payment_risk_review (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  case_id VARCHAR(36) NOT NULL,
  expected_event_id VARCHAR(128) NOT NULL,
  reviewer VARCHAR(128) NOT NULL,
  decision VARCHAR(40) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT fk_payment_review_case FOREIGN KEY(case_id) REFERENCES payment_risk_case(id)
);

ALTER TABLE payment_order ADD COLUMN failure_code VARCHAR(80);
ALTER TABLE payment_order ADD COLUMN reconcile_after TIMESTAMP(6);
-- Provider namespaces are independent (including live/sandbox and future adapters).
ALTER TABLE payment_order DROP INDEX uq_payment_link;
ALTER TABLE payment_order DROP INDEX uq_payment_capture;
ALTER TABLE payment_order ADD CONSTRAINT uq_payment_provider_link UNIQUE(provider,provider_link_id);
ALTER TABLE payment_order ADD CONSTRAINT uq_payment_provider_capture UNIQUE(provider,provider_capture_id);
CREATE INDEX idx_payment_order_owner_status ON payment_order(creator_id,status);
CREATE INDEX idx_payment_order_reconcile ON payment_order(provider,status,reconcile_after);
