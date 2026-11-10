-- 每日次数型配额：跨实例共享，给不直接计费的 AI 调用封顶成本。
-- -1 = 不限；0 = 禁止；正整数 = 实际次数上限（与 usage-limits.properties 的约定一致）。
CREATE TABLE daily_quota (
    creator_id VARCHAR(36) NOT NULL,
    bucket VARCHAR(32) NOT NULL,
    usage_day DATE NOT NULL,
    used_count INT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY(creator_id, bucket, usage_day),
    CONSTRAINT fk_daily_quota_creator FOREIGN KEY(creator_id) REFERENCES creator(id)
);
CREATE INDEX idx_daily_quota_day ON daily_quota(usage_day);

-- AI 改写消费流水：免费额度内的调用同样记账，用于成本核算与审计。
-- 唯一键 (creator_id, request_key) 同时承担幂等职责：同一请求重复提交不会重复扣费。
CREATE TABLE rewrite_consumption (
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
    CONSTRAINT fk_rewrite_consumption_creator FOREIGN KEY(creator_id) REFERENCES creator(id)
);
CREATE INDEX idx_rewrite_consumption_created ON rewrite_consumption(created_at);
-- 在途请求统计：跨实例统计同时执行的改写数量，用于全局与单用户并发上限。
CREATE INDEX idx_rewrite_consumption_reserved ON rewrite_consumption(status, creator_id);
