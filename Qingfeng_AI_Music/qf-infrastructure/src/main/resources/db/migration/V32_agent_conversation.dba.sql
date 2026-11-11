-- V32 · 对话式 Agent 创作会话（DBA 手工执行版，W1）
-- 与 V32__agent_conversation.sql 语义一致，仅多出 IF NOT EXISTS 幂等保护。
-- 文件名为单下划线 V32_agent_conversation.dba.sql：不符合 Flyway 的 V<version>__<desc>.sql 命名，
-- 因此 Flyway 会跳过本文件，不会与同版本 Flyway 迁移冲突；请勿改成双下划线。
-- ⚠️ 编号待上线前复核：本环境未直连生产 flyway_schema_history；V31 已被 V31__rewrite_charges.sql 占用，
--    故顺延为 V32。上线前须只读核对生产最大版本；若 V32 亦被占用则继续顺延。
-- 当前为单会话设计：agent_conversation.creator_id 唯一。多会话需去掉该 UNIQUE 并增加会话选择逻辑。
-- state 固定枚举：PENDING / RUNNING / DONE / FAILED；kind 固定枚举：TEXT / TOOL_CARD / PLACEHOLDER。
-- 部署侧按既有约定关闭 Flyway、表结构由 DBA 手工同步；执行前请确认已备份且目标表均不存在。
CREATE TABLE IF NOT EXISTS agent_conversation (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    creator_id VARCHAR(36) NOT NULL,
    current_work_id VARCHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_agent_conversation_creator UNIQUE(creator_id),
    CONSTRAINT fk_agent_conversation_creator FOREIGN KEY(creator_id) REFERENCES creator(id)
);
CREATE TABLE IF NOT EXISTS agent_message (
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
CREATE TABLE IF NOT EXISTS agent_conversation_state (
    conversation_id VARCHAR(36) NOT NULL PRIMARY KEY,
    current_song_json TEXT NULL,
    revision BIGINT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_agent_state_conversation FOREIGN KEY(conversation_id) REFERENCES agent_conversation(id)
);
