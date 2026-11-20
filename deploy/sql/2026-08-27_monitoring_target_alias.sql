-- 모니터링 대상 별칭 필드 (feat/topology-enhancement)
-- VM PostgreSQL은 ddl-auto: validate 이므로 배포 전 수동 실행 필요:
--   psql -U aims -d aims -f 2026-08-27_monitoring_target_alias.sql

ALTER TABLE monitoring_target ADD COLUMN IF NOT EXISTS alias VARCHAR(100);
