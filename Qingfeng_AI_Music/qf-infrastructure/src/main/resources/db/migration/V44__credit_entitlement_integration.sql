-- V44 integrates financial entitlements only after immutable canonical core V1..V43.
-- Core V42 is shared_download_entitlement, checksum -500011050, from b8a924f.
-- The isolated c780 payment V40 is preserved byte-for-byte as a TEST-ONLY fixture.
-- Its database history is incompatible with this chain: do not repair, baseline,
-- renumber its history, or skip validation to upgrade that isolated experiment.
-- Financial protocol 3, owner-first locking, scoped facts, and PREPARING remain
-- unchanged. No download grant, historical identity, balance, FIFO, or approval
-- is created or inferred. Stop old writers and retain a verified pre-cutover backup.
-- DDL implicitly commits; this migration never activates financial writes.
-- Enforce actual core43 history AND download structure before persistent financial
-- DDL. The temporary CHECK guard deliberately aborts on any mismatch.
CREATE TEMPORARY TABLE p0_v41_core40_prerequisite (
  verified INTEGER NOT NULL,
  CONSTRAINT chk_p0_v41_core40_prerequisite CHECK (verified = 1)
) ENGINE=InnoDB;
INSERT INTO p0_v41_core40_prerequisite(verified)
SELECT CASE WHEN
  (SELECT COUNT(*) FROM flyway_schema_history WHERE version='40' AND script='V40__artist_profiles_and_releases.sql' AND checksum=1424328127 AND success=1 AND type='SQL') = 1
  AND (SELECT COUNT(*) FROM flyway_schema_history WHERE version='40') = 1
  AND   (SELECT COUNT(*) FROM flyway_schema_history WHERE version='41' AND script='V41__artist_home_customization.sql' AND checksum=-860312848 AND success=1 AND type='SQL') = 1
  AND (SELECT COUNT(*) FROM flyway_schema_history WHERE version='41') = 1
  AND   (SELECT COUNT(*) FROM flyway_schema_history WHERE version='43' AND script='V43__lyric_revision_custom_sections.sql' AND checksum=-2135468013 AND success=1 AND type='SQL') = 1
  AND (SELECT COUNT(*) FROM flyway_schema_history WHERE version='43') = 1
  AND   (SELECT COUNT(*) FROM flyway_schema_history WHERE version='42'
    AND script='V42__shared_download_entitlement.sql' AND checksum=-500011050
    AND success=1 AND type='SQL') = 1
  AND (SELECT COUNT(*) FROM flyway_schema_history WHERE version='42') = 1
  AND (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='shared_download_entitlement' AND TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB') = 1
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='shared_download_entitlement') = 6
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='shared_download_entitlement' AND IS_NULLABLE='NO' AND (
      (COLUMN_NAME IN ('id','owner_id','work_id','asset_id') AND DATA_TYPE='varchar'
        AND CHARACTER_MAXIMUM_LENGTH=36 AND COLUMN_DEFAULT IS NULL)
      OR (COLUMN_NAME='title' AND DATA_TYPE='varchar' AND CHARACTER_MAXIMUM_LENGTH=255
        AND COLUMN_DEFAULT IS NULL)
      OR (COLUMN_NAME='created_at' AND DATA_TYPE='timestamp' AND DATETIME_PRECISION=0
        AND LOWER(REPLACE(COLUMN_DEFAULT,'()',''))='current_timestamp'))) = 6
  AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='shared_download_entitlement' AND INDEX_NAME='PRIMARY') = 1
  AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='shared_download_entitlement' AND INDEX_NAME='PRIMARY' AND NON_UNIQUE=0
    AND SEQ_IN_INDEX=1 AND COLUMN_NAME='id' AND SUB_PART IS NULL) = 1
  AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='shared_download_entitlement' AND INDEX_NAME='uq_shared_download_owner_asset') = 3
  AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='shared_download_entitlement' AND INDEX_NAME='uq_shared_download_owner_asset'
    AND NON_UNIQUE=0 AND SUB_PART IS NULL AND (
      (SEQ_IN_INDEX=1 AND COLUMN_NAME='owner_id') OR (SEQ_IN_INDEX=2 AND COLUMN_NAME='work_id')
      OR (SEQ_IN_INDEX=3 AND COLUMN_NAME='asset_id'))) = 3
  AND (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='shared_download_entitlement' AND REFERENCED_TABLE_NAME IS NOT NULL) = 3
  AND (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='shared_download_entitlement' AND REFERENCED_TABLE_SCHEMA=DATABASE()
    AND REFERENCED_COLUMN_NAME='id' AND ORDINAL_POSITION=1 AND (
      (CONSTRAINT_NAME='fk_shared_download_owner' AND COLUMN_NAME='owner_id' AND REFERENCED_TABLE_NAME='creator')
      OR (CONSTRAINT_NAME='fk_shared_download_work' AND COLUMN_NAME='work_id' AND REFERENCED_TABLE_NAME='music_work')
      OR (CONSTRAINT_NAME='fk_shared_download_asset' AND COLUMN_NAME='asset_id' AND REFERENCED_TABLE_NAME='media_asset'))) = 3
THEN 1 ELSE 0 END;
DROP TEMPORARY TABLE p0_v41_core40_prerequisite;
SELECT id,owner_id,work_id,asset_id,title,created_at FROM shared_download_entitlement WHERE 1=0;

-- Validate the existing core balance columns; never recreate core V34 tables.
SELECT owner_id, balance, updated_at FROM credit_account WHERE 1=0;
SELECT payment_risk_frozen FROM creator WHERE 1=0;

CREATE TABLE p0_credit_writer_policy (
  id INTEGER NOT NULL PRIMARY KEY,
  epoch VARCHAR(64) NOT NULL,
  protocol INTEGER NOT NULL,
  CONSTRAINT chk_p0_writer_policy_id CHECK (id = 1),
  CONSTRAINT chk_p0_writer_protocol CHECK (protocol = 3)
) ENGINE=InnoDB;
INSERT INTO p0_credit_writer_policy(id, epoch, protocol) VALUES (1, UUID(), 3);

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
  provider VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  merchant VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  environment VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  owner_id VARCHAR(36) NOT NULL,
  capture_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  CONSTRAINT fk_p0_binding_order FOREIGN KEY (order_id) REFERENCES payment_order(id),
  CONSTRAINT uq_p0_provider_payment UNIQUE (provider, merchant, environment, capture_id),
  INDEX idx_p0_binding_owner (owner_id, order_id)
) ENGINE=InnoDB;

CREATE TABLE p0_credit_refund (
  provider VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  merchant VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  environment VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  external_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  owner_id VARCHAR(36) NOT NULL,
  order_id VARCHAR(36) NOT NULL,
  capture_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  amount_minor BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  PRIMARY KEY (provider, merchant, environment, external_id),
  CONSTRAINT fk_p0_refund_order FOREIGN KEY (order_id) REFERENCES payment_order(id),
  CONSTRAINT chk_p0_refund_amount CHECK (amount_minor > 0)
) ENGINE=InnoDB;

CREATE TABLE p0_credit_dispute (
  provider VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  merchant VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  environment VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  external_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  owner_id VARCHAR(36) NOT NULL,
  order_id VARCHAR(36) NOT NULL,
  capture_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
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
  provider VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  merchant VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  environment VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  external_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  owner_id VARCHAR(36) NOT NULL,
  order_id VARCHAR(36) NOT NULL,
  dispute_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
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
  kind VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
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
  IF COALESCE((SELECT protocol FROM p0_credit_writer_policy WHERE id=1), 0) <> 3
     OR COALESCE((SELECT status FROM p0_credit_cutover_state WHERE id=1), '') <> 'ACTIVE'
     OR COALESCE((SELECT epoch FROM p0_credit_writer_policy WHERE id=1), '') = ''
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

CREATE TABLE p0_credit_refund_terminal (
  provider VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  merchant VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  environment VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  refund_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  owner_id VARCHAR(36) NOT NULL,
  order_id VARCHAR(36) NOT NULL,
  capture_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
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
  provider VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  merchant VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  environment VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  external_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  owner_id VARCHAR(36) NOT NULL,
  order_id VARCHAR(36) NOT NULL,
  refund_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  capture_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
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
  provider VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  merchant VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  environment VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  dispute_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  order_id VARCHAR(36) NOT NULL,
  capture_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  owner_id VARCHAR(36) NOT NULL,
  loss_minor BIGINT NOT NULL,
  withdrawal_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  charge_balance_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
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


-- Scope the existing core inbox without assigning an invented merchant to old events.
-- Existing rows retain their payload, event identity, time and state in LEGACY_UNKNOWN.
ALTER TABLE payment_webhook_inbox
  DROP PRIMARY KEY,
  DROP INDEX idx_payment_inbox_due,
  MODIFY provider VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  MODIFY event_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  ADD COLUMN merchant_scope VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'LEGACY_UNKNOWN',
  ADD COLUMN env_scope VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'LEGACY_UNKNOWN',
  ADD PRIMARY KEY (provider, merchant_scope, env_scope, event_id),
  ADD INDEX idx_payment_inbox_due (provider, merchant_scope, env_scope, status, next_attempt_at, lease_until);

-- Manual resumes and automatic provider waits are distinct, immutable evidence.
CREATE TABLE payment_inbox_review (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  provider VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  merchant_scope VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  env_scope VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  event_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  reviewer VARCHAR(128) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  action VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT fk_payment_inbox_review_event FOREIGN KEY (provider, merchant_scope, env_scope, event_id)
    REFERENCES payment_webhook_inbox(provider, merchant_scope, env_scope, event_id),
  CONSTRAINT chk_payment_inbox_review_action CHECK (action = 'RESUME'),
  INDEX idx_payment_review_scope_created (provider, merchant_scope, env_scope, event_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE payment_inbox_attempt (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  provider VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  merchant_scope VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  env_scope VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  event_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  attempt INTEGER NOT NULL,
  result VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  evidence_id VARCHAR(512) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT fk_payment_inbox_attempt_event FOREIGN KEY (provider, merchant_scope, env_scope, event_id)
    REFERENCES payment_webhook_inbox(provider, merchant_scope, env_scope, event_id),
  CONSTRAINT chk_payment_inbox_attempt_number CHECK (attempt >= 0),
  CONSTRAINT chk_payment_inbox_attempt_result CHECK (result IN ('WAITING','DONE','FAILED','MANUAL')),
  INDEX idx_payment_attempt_scope_created (provider, merchant_scope, env_scope, event_id, created_at)
) ENGINE=InnoDB;

CREATE TRIGGER payment_inbox_review_immutable_update BEFORE UPDATE ON payment_inbox_review FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PAYMENT_INBOX_REVIEW_APPEND_ONLY';
CREATE TRIGGER payment_inbox_review_immutable_delete BEFORE DELETE ON payment_inbox_review FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PAYMENT_INBOX_REVIEW_APPEND_ONLY';
CREATE TRIGGER payment_inbox_attempt_immutable_update BEFORE UPDATE ON payment_inbox_attempt FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PAYMENT_INBOX_ATTEMPT_APPEND_ONLY';
CREATE TRIGGER payment_inbox_attempt_immutable_delete BEFORE DELETE ON payment_inbox_attempt FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PAYMENT_INBOX_ATTEMPT_APPEND_ONLY';

-- All financial return adapters must reserve this identity under the owner lock.
-- An exact replay is allowed only after comparing every immutable binding field.
CREATE TABLE p0_credit_native_identity (
  provider VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  merchant VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  environment VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  external_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  owner_id VARCHAR(36) NOT NULL,
  order_id VARCHAR(36) NOT NULL,
  capture_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  kind VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  resource_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  amount_minor BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (provider, merchant, environment, external_id),
  CONSTRAINT uq_p0_native_resource UNIQUE (provider, merchant, environment, kind, resource_id),
  CONSTRAINT fk_p0_native_owner FOREIGN KEY (owner_id) REFERENCES creator(id),
  CONSTRAINT fk_p0_native_order FOREIGN KEY (order_id) REFERENCES payment_order(id),
  CONSTRAINT chk_p0_native_identity CHECK (amount_minor > 0 AND kind IN ('REFUND_BINDING','DISPUTE_BINDING','REFUND_RETURN','DISPUTE_RETURN')),
  INDEX idx_p0_native_owner_order (owner_id, order_id)
) ENGINE=InnoDB;

CREATE TRIGGER p0_native_identity_immutable_update BEFORE UPDATE ON p0_credit_native_identity FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='NATIVE_FINANCIAL_IDENTITY_APPEND_ONLY';
CREATE TRIGGER p0_native_identity_immutable_delete BEFORE DELETE ON p0_credit_native_identity FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='NATIVE_FINANCIAL_IDENTITY_APPEND_ONLY';
