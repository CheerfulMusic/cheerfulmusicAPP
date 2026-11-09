-- 埋点补充：登录用户归属与页面停留时长。
-- creator_id 由服务端从 QF_SESSION 解析，绝不信任前端传值；
-- 邮箱 / 手机号不落埋点表，看板通过 creator_id 关联
-- creator_google_identity / creator_oidc_identity 获取账号信息。
-- duration_ms 来自 page_leave 事件，服务端限制在 0..86400000。
ALTER TABLE site_event ADD COLUMN creator_id VARCHAR(36);
ALTER TABLE site_event ADD COLUMN duration_ms BIGINT;
CREATE INDEX idx_site_event_creator ON site_event(creator_id,created_at);
