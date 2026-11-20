-- 물리 자산 확장 필드 (feat/asset-extension)
-- VM PostgreSQL은 ddl-auto: validate 이므로 배포 전 수동 실행 필요:
--   psql -U aims -d aims -f 2026-08-27_asset_extension.sql

ALTER TABLE assets ADD COLUMN IF NOT EXISTS category_major     VARCHAR(50);
ALTER TABLE assets ADD COLUMN IF NOT EXISTS category_middle    VARCHAR(50);
ALTER TABLE assets ADD COLUMN IF NOT EXISTS category_minor     VARCHAR(50);
ALTER TABLE assets ADD COLUMN IF NOT EXISTS category_detail    VARCHAR(50);
ALTER TABLE assets ADD COLUMN IF NOT EXISTS equipment_location VARCHAR(200);
ALTER TABLE assets ADD COLUMN IF NOT EXISTS rack_location      VARCHAR(100);
ALTER TABLE assets ADD COLUMN IF NOT EXISTS mount_point        VARCHAR(20);
ALTER TABLE assets ADD COLUMN IF NOT EXISTS unit_size          INTEGER;
ALTER TABLE assets ADD COLUMN IF NOT EXISTS system_type        VARCHAR(50);
ALTER TABLE assets ADD COLUMN IF NOT EXISTS source             VARCHAR(20) NOT NULL DEFAULT 'SELF';
ALTER TABLE assets ADD COLUMN IF NOT EXISTS external_id        VARCHAR(100);

-- 1세부 데이터 동기화 upsert 키
CREATE UNIQUE INDEX IF NOT EXISTS uq_assets_source_external_id
    ON assets (source, external_id) WHERE external_id IS NOT NULL;
