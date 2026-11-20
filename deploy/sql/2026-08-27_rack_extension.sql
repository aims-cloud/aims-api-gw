-- 랙실장도 배치/필터 필드 (feat/rack-layout)
-- VM PostgreSQL은 ddl-auto: validate 이므로 배포 전 수동 실행 필요:
--   psql -U aims -d aims -f 2026-08-27_rack_extension.sql

ALTER TABLE racks ADD COLUMN IF NOT EXISTS category    VARCHAR(50);
ALTER TABLE racks ADD COLUMN IF NOT EXISTS location    VARCHAR(100);
ALTER TABLE racks ADD COLUMN IF NOT EXISTS floor_no    VARCHAR(20);
ALTER TABLE racks ADD COLUMN IF NOT EXISTS zone_name   VARCHAR(50);
ALTER TABLE racks ADD COLUMN IF NOT EXISTS row_no      VARCHAR(20);
ALTER TABLE racks ADD COLUMN IF NOT EXISTS position_no VARCHAR(20);
