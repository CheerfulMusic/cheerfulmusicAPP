-- 站内用户行为事件。移植自 tk-tracking v1.2 的 wp_tk_site_events，
-- 去掉 WooCommerce 专用字段，只保留匿名标识与脱敏页面路径。
-- 隐私边界：不存故事、歌词、邮箱、手机号、礼物 token、OAuth 授权码；
-- page_path 由前端脱敏、后端二次校验，referrer 去掉 query/hash。
CREATE TABLE site_event (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    visitor_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    event VARCHAR(50) NOT NULL,
    page_path VARCHAR(512) NOT NULL,
    referrer VARCHAR(512) NOT NULL,
    payload TEXT,
    created_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_site_event_created ON site_event(created_at);
CREATE INDEX idx_site_event_event ON site_event(event,created_at);
CREATE INDEX idx_site_event_visitor ON site_event(visitor_id,created_at);
