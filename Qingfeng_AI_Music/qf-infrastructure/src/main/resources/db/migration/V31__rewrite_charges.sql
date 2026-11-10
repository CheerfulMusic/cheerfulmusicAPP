-- 兼容已通过 JDBC 手工建表的环境。索引随新表一起创建，既有表保留原样。
-- 每日次数型配额：跨实例共享，给不直接计费的 AI 调用封顶成本。
-- -1 = 不限；0 = 禁止；正整数 = 实际次数上限（与 usage-limits.properties 的约定一致）。
CREATE TABLE IF NOT EXISTS daily_quota (
    creator_id VARCHAR(36) NOT NULL,
    bucket VARCHAR(32) NOT NULL,
    usage_day DATE NOT NULL,
    used_count INT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY(creator_id, bucket, usage_day),
    CONSTRAINT fk_daily_quota_creator FOREIGN KEY(creator_id) REFERENCES creator(id),
    INDEX idx_daily_quota_day (usage_day)
);

-- AI 改写消费流水：免费额度内的调用同样记账，用于成本核算与审计。
-- 唯一键 (creator_id, request_key) 同时承担幂等职责：同一请求重复提交不会重复扣费。
CREATE TABLE IF NOT EXISTS rewrite_consumption (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    creator_id VARCHAR(36) NOT NULL,
    request_key VARCHAR(36) NOT NULL,
    amount BIGINT NOT NULL,
    charged INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    result_text TEXT,
    refunded INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_rewrite_consumption UNIQUE(creator_id, request_key),
    CONSTRAINT fk_rewrite_consumption_creator FOREIGN KEY(creator_id) REFERENCES creator(id),
    INDEX idx_rewrite_consumption_created (created_at),
    INDEX idx_rewrite_consumption_reserved (status, creator_id)
);
