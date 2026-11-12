-- Durable request identity, conservative planning-token reservation and shared admission.
-- No automatic replay of RUNNING/UNCERTAIN requests: external outcomes may be unknown.
CREATE TABLE agent_admission_lock (id INT NOT NULL PRIMARY KEY);
INSERT INTO agent_admission_lock(id) VALUES(1);
CREATE TABLE agent_request_admission (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    creator_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    request_key VARCHAR(36) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    usage_day DATE NOT NULL,
    reserved_tokens BIGINT NOT NULL,
    lease_until TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_agent_admission_request UNIQUE(conversation_id,request_key),
    CONSTRAINT fk_agent_admission_creator FOREIGN KEY(creator_id) REFERENCES creator(id)
);
CREATE INDEX ix_agent_admission_active ON agent_request_admission(state,lease_until,creator_id);
CREATE INDEX ix_agent_admission_usage ON agent_request_admission(usage_day,creator_id,reserved_tokens);
CREATE INDEX ix_agent_admission_rate ON agent_request_admission(created_at,creator_id);
