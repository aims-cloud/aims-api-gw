-- 모니터링 대상 ↔ 물리 자산 연결 (feat/monitoring)
-- 자원 탐색기 물리 호스트 하위 모니터링 항목은 해당 자산에 등록된 exporter(monitoring_target.exporter_type)로 판단한다.
-- hostname/IP 문자열 비교로 추정하지 않고 asset_id 로 명시적으로 연결한다 (docs/06 자원 탐색기 트리 설계 §9.4).
-- VM PostgreSQL은 ddl-auto: validate 이므로 배포 전 수동 실행 필요:
--   psql -U aims -d aims -f 2026-09-04_monitoring_target_asset.sql

ALTER TABLE monitoring_target ADD COLUMN IF NOT EXISTS asset_id BIGINT REFERENCES assets (id);

-- 자원 탐색기 조회 경로: 호스트(자산) 목록 → 등록된 exporter 목록
CREATE INDEX IF NOT EXISTS idx_monitoring_target_asset_id ON monitoring_target (asset_id);
