-- Apply separately before enabling the version-management release. No financial migration.
-- Match the existing identifier column; database defaults can differ from legacy tables.
SET @qf_media_charset = (SELECT character_set_name FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'media_asset' AND column_name = 'id');
SET @qf_media_collation = (SELECT collation_name FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'media_asset' AND column_name = 'id');
SET @qf_annotation_ddl = CONCAT('CREATE TABLE IF NOT EXISTS media_asset_annotation (
 asset_id VARCHAR(36) NOT NULL PRIMARY KEY,
 note VARCHAR(500) NOT NULL DEFAULT '''',
 note_version BIGINT NOT NULL DEFAULT 0,
 deleted_at TIMESTAMP(6) NULL
) ENGINE=InnoDB DEFAULT CHARACTER SET ', @qf_media_charset, ' COLLATE ', @qf_media_collation);
PREPARE qf_annotation_statement FROM @qf_annotation_ddl;
EXECUTE qf_annotation_statement;
DEALLOCATE PREPARE qf_annotation_statement;
