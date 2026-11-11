-- V33 · 国家分布（DBA 手工执行版）
-- 与 V33__site_event_geo.sql 语义一致，仅多出幂等保护。
-- ⚠️ 本文件刻意放在 db/dba/ 而不是 db/migration/：db/migration 下只允许规范的
--    V<version>__<description>.sql。非规范文件会被 Flyway 的版本扫描、以及发布工具链
--    （release_gate 的迁移名校验、契约生成器的版本号解析）判为非法迁移，导致发布时
--    无法产出 release-contract.json / schema-evidence.json。请勿移回 db/migration/。
-- ⚠️ 编号待上线前复核：本环境未直连生产 flyway_schema_history；上线前须只读核对生产最大版本，
--    若 V33 已被占用则继续顺延（同时改 db/migration 与 db/dba 两处文件名与内容）。
-- 部署侧按既有约定关闭 Flyway、表结构由 DBA 手工同步；执行前请确认已备份。
-- MySQL 不支持 ADD COLUMN IF NOT EXISTS，改用 information_schema 判断实现幂等。
SET @exist := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'site_event' AND column_name = 'country');
SET @sql := IF(@exist = 0, 'ALTER TABLE site_event ADD COLUMN country VARCHAR(64) NULL', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
             WHERE table_schema = DATABASE() AND table_name = 'site_event' AND index_name = 'idx_site_event_country');
SET @sql2 := IF(@idx = 0, 'CREATE INDEX idx_site_event_country ON site_event(country)', 'SELECT 1');
PREPARE stmt2 FROM @sql2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
