CREATE TABLE credit_lock (id INTEGER PRIMARY KEY);
INSERT INTO credit_lock(id) VALUES (1);
CREATE TABLE credit_ledger (
    entry_type VARCHAR(16) NOT NULL,
    reference_id VARCHAR(160) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    amount BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY(entry_type, reference_id),
    CONSTRAINT fk_credit_owner FOREIGN KEY(owner_id) REFERENCES creator(id)
);
CREATE INDEX idx_credit_owner ON credit_ledger(owner_id);
