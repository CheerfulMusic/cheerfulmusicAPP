CREATE TABLE ai_capacity_job (
  id CHAR(64) NOT NULL PRIMARY KEY,
  lane VARCHAR(12) NOT NULL,
  scope_id VARCHAR(36) NOT NULL,
  request_key VARCHAR(36) NOT NULL,
  owner_id VARCHAR(36) NOT NULL,
  active_owner VARCHAR(36),
  fingerprint CHAR(64) NOT NULL,
  payload MEDIUMTEXT,
  state VARCHAR(12) NOT NULL,
  stage VARCHAR(12) NOT NULL,
  error_code VARCHAR(64),
  dependency_id CHAR(64),
  created_at TIMESTAMP(6) NOT NULL,
  queue_until TIMESTAMP(6) NOT NULL,
  next_attempt_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  lease_until TIMESTAMP(6),
  lease_token VARCHAR(36),
  CONSTRAINT uq_ai_capacity_request UNIQUE(lane,scope_id,request_key),
  CONSTRAINT uq_ai_capacity_owner UNIQUE(lane,active_owner)
);
-- Older databases can have a different default collation from creator.id.
-- Match the actual parent column before installing the foreign key on this empty table.
/*!80000 SET @qf_ai_owner_column = (
  SELECT CONCAT('ALTER TABLE ai_capacity_job MODIFY owner_id VARCHAR(36) CHARACTER SET ',
                CHARACTER_SET_NAME, ' COLLATE ', COLLATION_NAME, ' NOT NULL')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'creator' AND column_name = 'id'
) */;
/*!80000 PREPARE qf_ai_owner_ddl FROM @qf_ai_owner_column */;
/*!80000 EXECUTE qf_ai_owner_ddl */;
/*!80000 DEALLOCATE PREPARE qf_ai_owner_ddl */;
ALTER TABLE ai_capacity_job ADD CONSTRAINT fk_ai_capacity_owner FOREIGN KEY(owner_id) REFERENCES creator(id);
CREATE INDEX ix_ai_capacity_ready ON ai_capacity_job(lane,stage,state,next_attempt_at,id);
CREATE INDEX ix_ai_capacity_lease ON ai_capacity_job(lane,state,lease_until,id);
CREATE INDEX ix_ai_capacity_queue_expiry ON ai_capacity_job(lane,state,queue_until,id);

CREATE TABLE ai_capacity_slot (
  lane VARCHAR(12) NOT NULL,
  purpose VARCHAR(8) NOT NULL,
  slot_no INT NOT NULL,
  job_id CHAR(64),
  PRIMARY KEY(lane,purpose,slot_no),
  CONSTRAINT uq_ai_capacity_slot_job UNIQUE(lane,purpose,job_id)
);
CREATE INDEX ix_ai_capacity_available ON ai_capacity_slot(lane,purpose,job_id,slot_no);

INSERT INTO ai_capacity_slot(lane,purpose,slot_no)
SELECT lane_names.lane, purposes.purpose, digits.n
FROM (SELECT 'LYRICS' AS lane UNION ALL SELECT 'COVER' UNION ALL SELECT 'AGENT') lane_names
CROSS JOIN (SELECT 'ACTIVE' AS purpose UNION ALL SELECT 'EXECUTE') purposes
CROSS JOIN (
  SELECT ones.n+10*tens.n+100*hundreds.n+1000*thousands.n AS n
  FROM (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
  CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
              UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
  CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
              UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds
  CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1) thousands
) digits
WHERE purposes.purpose='ACTIVE' OR digits.n<1000;

CREATE TABLE ai_capacity_model_usage (
  account_scope VARCHAR(70) NOT NULL,
  period_kind VARCHAR(6) NOT NULL,
  period_start TIMESTAMP NOT NULL,
  requests BIGINT NOT NULL,
  tokens BIGINT NOT NULL,
  PRIMARY KEY(account_scope,period_kind,period_start)
);
