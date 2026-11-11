-- V33 · 国家分布：site_event 增加 country 列（仅存派生的国家名，不存原始 IP）。
-- 与 db/dba/V33_site_event_geo.dba.sql 语义一致；本文件为 Flyway 规范迁移（命名 V<v>__<desc>.sql）。
-- 生产按既有约定关闭 Flyway、由 DBA 手工执行 db/dba/ 下的幂等脚本，本文件主要供本地开发 Flyway 与构建守卫使用。
ALTER TABLE site_event ADD COLUMN country VARCHAR(64) NULL;
CREATE INDEX idx_site_event_country ON site_event(country);
