-- Repair only the optional annotation table when an earlier release used database defaults.
-- The caller must back up this table and validate the source identifier metadata first.
SET @qf_media_charset = (SELECT character_set_name FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'media_asset' AND column_name = 'id');
SET @qf_media_collation = (SELECT collation_name FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'media_asset' AND column_name = 'id');
SET @qf_annotation_ddl = CONCAT('ALTER TABLE media_asset_annotation CONVERT TO CHARACTER SET ',
 @qf_media_charset, ' COLLATE ', @qf_media_collation);
PREPARE qf_annotation_statement FROM @qf_annotation_ddl;
EXECUTE qf_annotation_statement;
DEALLOCATE PREPARE qf_annotation_statement;
