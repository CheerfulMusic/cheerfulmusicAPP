-- Paid chat allowances persist across conversations and days.
CREATE TABLE agent_chat_allowance (
    creator_id VARCHAR(36) PRIMARY KEY,
    remaining_turns BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_agent_chat_allowance_creator FOREIGN KEY (creator_id) REFERENCES creator(id),
    CONSTRAINT ck_agent_chat_allowance_nonnegative CHECK (remaining_turns >= 0)
);
ALTER TABLE agent_request_admission ADD COLUMN paid_turn INT NOT NULL DEFAULT 0;
ALTER TABLE agent_request_admission ADD COLUMN purchased_pack INT NOT NULL DEFAULT 0;
