-- V32 · 对话式 Agent 创作会话（W1）
-- ⚠️ 编号待上线前复核：本环境未直连生产 flyway_schema_history；本仓库原计划的 V31
--    已被 V31__rewrite_charges.sql 占用（并行积分会话 V29/V30 改名后顺延为 V30/V31），故顺延为 V32。
--    上线前须只读核对生产最大版本；若 V32 亦被占用，则继续顺延，不得改写任何已有迁移文件。
-- 当前为单会话设计：agent_conversation.creator_id 唯一。多会话需去掉该 UNIQUE 并增加会话选择逻辑。
-- 双轨交付：本文件供 Flyway / H2 test 自动执行；V32_agent_conversation.dba.sql 供 DBA 手工执行（带幂等保护）。
-- state 固定枚举：PENDING / RUNNING / DONE / FAILED；kind 固定枚举：TEXT / TOOL_CARD / PLACEHOLDER。
-- seq 的 UNIQUE(conversation_id, seq) 同时承担 W3 的 messageId/seq 去重职责。
CREATE TABLE agent_conversation (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    creator_id VARCHAR(36) NOT NULL,
    current_work_id VARCHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_agent_conversation_creator UNIQUE(creator_id),
    CONSTRAINT fk_agent_conversation_creator FOREIGN KEY(creator_id) REFERENCES creator(id)
);
CREATE TABLE agent_message (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    seq BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    state VARCHAR(16) NOT NULL,
    content_json TEXT NULL,
    task_id VARCHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_agent_message_seq UNIQUE(conversation_id, seq),
    CONSTRAINT fk_agent_message_conversation FOREIGN KEY(conversation_id) REFERENCES agent_conversation(id)
);
CREATE TABLE agent_conversation_state (
    conversation_id VARCHAR(36) NOT NULL PRIMARY KEY,
    current_song_json TEXT NULL,
    revision BIGINT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_agent_state_conversation FOREIGN KEY(conversation_id) REFERENCES agent_conversation(id)
);
