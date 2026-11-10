-- 创作消费流水：与 credit_ledger（只记金额增减）分开，记录任务、消费类型与任务状态，便于对账与核查。
-- 唯一键 (消费类型, 任务, 请求键) 同时承担幂等职责：同一请求重复提交不会重复记账。
CREATE TABLE credit_consumption (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    consumption_type VARCHAR(32) NOT NULL,
    work_id VARCHAR(36) NOT NULL,
    request_key VARCHAR(36) NOT NULL,
    amount BIGINT NOT NULL,
    task_status VARCHAR(16) NOT NULL,
    refunded INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_credit_consumption UNIQUE(consumption_type, work_id, request_key),
    CONSTRAINT fk_credit_consumption_owner FOREIGN KEY(owner_id) REFERENCES creator(id)
);
CREATE INDEX idx_credit_consumption_owner ON credit_consumption(owner_id, created_at);
CREATE INDEX idx_credit_consumption_status ON credit_consumption(task_status);
