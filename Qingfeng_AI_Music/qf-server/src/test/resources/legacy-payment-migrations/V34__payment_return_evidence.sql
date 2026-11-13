-- Append-only refund terminal/return facts. Existing successful refunds and V33 remain intact.
-- No historical backfill: provider evidence and old-writer fencing are separate cutover gates.
CREATE TABLE p0_credit_refund_terminal (
  provider VARCHAR(24) NOT NULL,
  merchant VARCHAR(128) NOT NULL,
  environment VARCHAR(16) NOT NULL,
  refund_id VARCHAR(128) NOT NULL,
  owner_id VARCHAR(36) NOT NULL,
  order_id VARCHAR(36) NOT NULL,
  capture_id VARCHAR(128) NOT NULL,
  amount_minor BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  terminal_status VARCHAR(16) NOT NULL,
  evidence_id VARCHAR(512) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (provider, merchant, environment, refund_id),
  CONSTRAINT fk_p0_refund_terminal_order FOREIGN KEY (order_id) REFERENCES payment_order(id),
  CONSTRAINT chk_p0_refund_terminal CHECK (amount_minor > 0 AND terminal_status IN ('failed','canceled')),
  INDEX idx_p0_refund_terminal_order (order_id)
) ENGINE=InnoDB;

CREATE TABLE p0_credit_refund_return (
  provider VARCHAR(24) NOT NULL,
  merchant VARCHAR(128) NOT NULL,
  environment VARCHAR(16) NOT NULL,
  external_id VARCHAR(128) NOT NULL,
  owner_id VARCHAR(36) NOT NULL,
  order_id VARCHAR(36) NOT NULL,
  refund_id VARCHAR(128) NOT NULL,
  capture_id VARCHAR(128) NOT NULL,
  amount_minor BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  evidence_id VARCHAR(512) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (provider, merchant, environment, external_id),
  CONSTRAINT uq_p0_refund_full_return UNIQUE (provider, merchant, environment, refund_id),
  CONSTRAINT fk_p0_refund_return_order FOREIGN KEY (order_id) REFERENCES payment_order(id),
  CONSTRAINT fk_p0_refund_return_terminal FOREIGN KEY (provider,merchant,environment,refund_id) REFERENCES p0_credit_refund_terminal(provider,merchant,environment,refund_id),
  CONSTRAINT chk_p0_refund_return_amount CHECK (amount_minor > 0),
  INDEX idx_p0_refund_return_order (order_id)
) ENGINE=InnoDB;

-- Immutable proof of the native withdrawal behind one dispute loss. No FX conversion is stored.
CREATE TABLE p0_stripe_dispute_proof (
  provider VARCHAR(24) NOT NULL,
  merchant VARCHAR(128) NOT NULL,
  environment VARCHAR(16) NOT NULL,
  dispute_id VARCHAR(128) NOT NULL,
  order_id VARCHAR(36) NOT NULL,
  capture_id VARCHAR(128) NOT NULL,
  owner_id VARCHAR(36) NOT NULL,
  loss_minor BIGINT NOT NULL,
  withdrawal_id VARCHAR(128) NOT NULL,
  charge_balance_id VARCHAR(128) NOT NULL,
  settlement_currency CHAR(3) NOT NULL,
  settlement_principal_minor BIGINT NOT NULL,
  evidence_sha256 CHAR(64) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (provider,merchant,environment,dispute_id),
  CONSTRAINT uq_p0_stripe_withdrawal UNIQUE (provider,merchant,environment,withdrawal_id),
  CONSTRAINT fk_p0_stripe_proof_order FOREIGN KEY (order_id) REFERENCES payment_order(id),
  CONSTRAINT chk_p0_stripe_proof_amount CHECK (loss_minor > 0 AND settlement_principal_minor > 0)
) ENGINE=InnoDB;

CREATE TRIGGER p0_refund_success_immutable_update BEFORE UPDATE ON p0_credit_refund FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='REFUND_SUCCESS_FACT_APPEND_ONLY';
CREATE TRIGGER p0_refund_success_immutable_delete BEFORE DELETE ON p0_credit_refund FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='REFUND_SUCCESS_FACT_APPEND_ONLY';
CREATE TRIGGER p0_refund_terminal_immutable_update BEFORE UPDATE ON p0_credit_refund_terminal FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='REFUND_TERMINAL_FACT_APPEND_ONLY';
CREATE TRIGGER p0_refund_terminal_immutable_delete BEFORE DELETE ON p0_credit_refund_terminal FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='REFUND_TERMINAL_FACT_APPEND_ONLY';
CREATE TRIGGER p0_refund_return_immutable_update BEFORE UPDATE ON p0_credit_refund_return FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='REFUND_RETURN_FACT_APPEND_ONLY';
CREATE TRIGGER p0_refund_return_immutable_delete BEFORE DELETE ON p0_credit_refund_return FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='REFUND_RETURN_FACT_APPEND_ONLY';
CREATE TRIGGER p0_stripe_proof_immutable_update BEFORE UPDATE ON p0_stripe_dispute_proof FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='DISPUTE_PROOF_FACT_APPEND_ONLY';
CREATE TRIGGER p0_stripe_proof_immutable_delete BEFORE DELETE ON p0_stripe_dispute_proof FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='DISPUTE_PROOF_FACT_APPEND_ONLY';

-- V33 writers require protocol=1 and must not continue summing raw successful refunds.
-- The epoch also rotates so already-held legacy session values cannot insert journal rows.
-- V33's original CHECK is replaced here; the original migration remains immutable.
ALTER TABLE p0_credit_writer_policy DROP CHECK chk_p0_writer_protocol;
UPDATE p0_credit_writer_policy SET protocol=2,epoch=UUID() WHERE id=1 AND protocol=1;
ALTER TABLE p0_credit_writer_policy ADD CONSTRAINT chk_p0_writer_protocol CHECK (protocol = 2);
