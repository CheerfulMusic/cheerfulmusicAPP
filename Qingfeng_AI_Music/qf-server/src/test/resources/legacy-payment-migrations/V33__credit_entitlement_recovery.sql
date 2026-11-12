-- P0 entitlement/recovery schema and database write fence. This does not backfill historical
-- balances; production cutover must first reconcile/backfill historical rights and stop old writers.
CREATE TABLE p0_credit_writer_policy (
  id INTEGER NOT NULL PRIMARY KEY,
  epoch VARCHAR(64) NOT NULL,
  protocol INTEGER NOT NULL,
  CONSTRAINT chk_p0_writer_policy_id CHECK (id = 1),
  CONSTRAINT chk_p0_writer_protocol CHECK (protocol = 1)
) ENGINE=InnoDB;
INSERT INTO p0_credit_writer_policy(id, epoch, protocol) VALUES (1, 'P0_CREDIT_WRITER_V1', 1);

CREATE TABLE p0_credit_cutover_state (
  id INTEGER NOT NULL PRIMARY KEY,
  status VARCHAR(16) NOT NULL,
  activated_at TIMESTAMP(6) NULL,
  evidence VARCHAR(128) NULL,
  CONSTRAINT chk_p0_credit_cutover_id CHECK (id = 1),
  CONSTRAINT chk_p0_credit_cutover_status CHECK (status IN ('PREPARING','ACTIVE'))
) ENGINE=InnoDB;
INSERT INTO p0_credit_cutover_state(id,status) VALUES (1,'PREPARING');

CREATE TABLE p0_credit_batch (
  reference VARCHAR(160) NOT NULL PRIMARY KEY,
  owner_id VARCHAR(36) NOT NULL,
  source_type VARCHAR(24) NOT NULL,
  source_id VARCHAR(160) NOT NULL,
  original BIGINT NOT NULL,
  consumed BIGINT NOT NULL DEFAULT 0,
  adjusted BIGINT NOT NULL DEFAULT 0,
  held BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT fk_p0_batch_owner FOREIGN KEY (owner_id) REFERENCES creator(id),
  -- A refund after consumption creates debt: consumed + adjusted may exceed original.
  CONSTRAINT chk_p0_batch_amounts CHECK (original >= 0 AND consumed >= 0 AND consumed <= original AND adjusted >= 0 AND adjusted <= original AND held >= 0 AND held <= GREATEST(0, original - consumed - adjusted)),
  INDEX idx_p0_batch_owner_fifo (owner_id, created_at, reference)
) ENGINE=InnoDB;

CREATE TABLE p0_credit_allocation (
  debit_reference VARCHAR(160) NOT NULL,
  batch_reference VARCHAR(160) NOT NULL,
  owner_id VARCHAR(36) NOT NULL,
  amount BIGINT NOT NULL,
  restored TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (debit_reference, batch_reference),
  CONSTRAINT fk_p0_allocation_batch FOREIGN KEY (batch_reference) REFERENCES p0_credit_batch(reference),
  CONSTRAINT fk_p0_allocation_owner FOREIGN KEY (owner_id) REFERENCES creator(id),
  CONSTRAINT chk_p0_allocation_amount CHECK (amount > 0 AND restored IN (0, 1)),
  INDEX idx_p0_allocation_owner (owner_id, debit_reference)
) ENGINE=InnoDB;

CREATE TABLE p0_credit_payment_binding (
  order_id VARCHAR(36) NOT NULL PRIMARY KEY,
  provider VARCHAR(24) NOT NULL,
  merchant VARCHAR(128) NOT NULL,
  environment VARCHAR(16) NOT NULL,
  owner_id VARCHAR(36) NOT NULL,
  capture_id VARCHAR(128) NOT NULL,
  CONSTRAINT fk_p0_binding_order FOREIGN KEY (order_id) REFERENCES payment_order(id),
  CONSTRAINT uq_p0_provider_payment UNIQUE (provider, merchant, environment, capture_id),
  INDEX idx_p0_binding_owner (owner_id, order_id)
) ENGINE=InnoDB;

CREATE TABLE p0_credit_refund (
  provider VARCHAR(24) NOT NULL,
  merchant VARCHAR(128) NOT NULL,
  environment VARCHAR(16) NOT NULL,
  external_id VARCHAR(128) NOT NULL,
  owner_id VARCHAR(36) NOT NULL,
  order_id VARCHAR(36) NOT NULL,
  capture_id VARCHAR(128) NOT NULL,
  amount_minor BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  PRIMARY KEY (provider, merchant, environment, external_id),
  CONSTRAINT fk_p0_refund_order FOREIGN KEY (order_id) REFERENCES payment_order(id),
  CONSTRAINT chk_p0_refund_amount CHECK (amount_minor > 0)
) ENGINE=InnoDB;

CREATE TABLE p0_credit_dispute (
  provider VARCHAR(24) NOT NULL,
  merchant VARCHAR(128) NOT NULL,
  environment VARCHAR(16) NOT NULL,
  external_id VARCHAR(128) NOT NULL,
  owner_id VARCHAR(36) NOT NULL,
  order_id VARCHAR(36) NOT NULL,
  capture_id VARCHAR(128) NOT NULL,
  amount_minor BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  version BIGINT NOT NULL,
  state VARCHAR(24) NOT NULL,
  loss_minor BIGINT NULL,
  PRIMARY KEY (provider, merchant, environment, external_id),
  CONSTRAINT fk_p0_dispute_order FOREIGN KEY (order_id) REFERENCES payment_order(id),
  CONSTRAINT chk_p0_dispute_amount CHECK (amount_minor > 0 AND version > 0 AND (loss_minor IS NULL OR loss_minor >= 0))
) ENGINE=InnoDB;

CREATE TABLE p0_credit_return (
  provider VARCHAR(24) NOT NULL,
  merchant VARCHAR(128) NOT NULL,
  environment VARCHAR(16) NOT NULL,
  external_id VARCHAR(128) NOT NULL,
  owner_id VARCHAR(36) NOT NULL,
  order_id VARCHAR(36) NOT NULL,
  dispute_id VARCHAR(128) NOT NULL,
  amount_minor BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  evidence_id VARCHAR(512) NOT NULL,
  PRIMARY KEY (provider, merchant, environment, external_id),
  CONSTRAINT fk_p0_return_order FOREIGN KEY (order_id) REFERENCES payment_order(id),
  CONSTRAINT chk_p0_return_amount CHECK (amount_minor > 0)
) ENGINE=InnoDB;

CREATE TABLE p0_credit_financial_audit (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_id VARCHAR(36) NOT NULL,
  fact VARCHAR(128) NOT NULL,
  delta BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT fk_p0_financial_audit_order FOREIGN KEY (order_id) REFERENCES payment_order(id),
  INDEX idx_p0_financial_audit_order (order_id, id)
) ENGINE=InnoDB;

CREATE TABLE p0_legacy_debit_mapping (
  reference VARCHAR(160) NOT NULL PRIMARY KEY,
  owner_id VARCHAR(36) NOT NULL,
  amount BIGINT NOT NULL,
  restored TINYINT NOT NULL DEFAULT 0,
  attribution VARCHAR(32) NOT NULL,
  CONSTRAINT fk_p0_legacy_debit_owner FOREIGN KEY (owner_id) REFERENCES creator(id),
  CONSTRAINT chk_p0_legacy_debit_amount CHECK (amount > 0 AND restored IN (0, 1))
) ENGINE=InnoDB;

CREATE TABLE p0_credit_recovery (
  id CHAR(64) NOT NULL PRIMARY KEY,
  owner_id VARCHAR(36) NOT NULL,
  kind VARCHAR(64) NOT NULL,
  payload TEXT NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL,
  retry_after TIMESTAMP(6) NULL,
  lease_token VARCHAR(36) NULL,
  lease_until TIMESTAMP(6) NULL,
  last_error VARCHAR(128) NULL,
  CONSTRAINT chk_p0_recovery_attempts CHECK (attempts >= 0),
  INDEX idx_p0_recovery_claim (status, retry_after, created_at)
) ENGINE=InnoDB;

CREATE TABLE p0_credit_recovery_audit (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  task_id CHAR(64) NOT NULL,
  attempt INTEGER NOT NULL,
  result VARCHAR(16) NOT NULL,
  evidence_id VARCHAR(128) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT fk_p0_recovery_audit_task FOREIGN KEY (task_id) REFERENCES p0_credit_recovery(id),
  INDEX idx_p0_recovery_audit_task (task_id, id)
) ENGINE=InnoDB;

CREATE TABLE p0_credit_recovery_approval (
  approval_id CHAR(64) NOT NULL PRIMARY KEY,
  task_id CHAR(64) NOT NULL,
  operator_id VARCHAR(128) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT fk_p0_recovery_approval_task FOREIGN KEY (task_id) REFERENCES p0_credit_recovery(id)
) ENGINE=InnoDB;

CREATE TRIGGER p0_credit_ledger_insert_fence BEFORE INSERT ON credit_ledger FOR EACH ROW
BEGIN
  IF COALESCE((SELECT epoch FROM p0_credit_writer_policy WHERE id=1), '') = ''
     OR @qf_credit_writer_epoch IS NULL
     OR @qf_credit_writer_epoch <> (SELECT epoch FROM p0_credit_writer_policy WHERE id=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='OLD_CREDIT_WRITER_FENCED';
  END IF;
END;

CREATE TRIGGER p0_credit_ledger_update_fence BEFORE UPDATE ON credit_ledger FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CREDIT_LEDGER_APPEND_ONLY';
END;

CREATE TRIGGER p0_credit_ledger_delete_fence BEFORE DELETE ON credit_ledger FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CREDIT_LEDGER_APPEND_ONLY';
END;
