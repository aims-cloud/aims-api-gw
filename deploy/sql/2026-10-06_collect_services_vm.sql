-- 수집 서비스 원장에 Tumblebug VM 소유 추가 (docs/11 §4).
-- 한 행은 물리 자산(asset_id) 또는 VM(tb_ns, tb_infra, tb_node) 중 하나만 갖는다. 등록 데이터가 있으므로 표를 지우지 않고 칸만 더한다.
-- 서비스 이름은 소유자 안에서 유일하다 - 자산은 기존 uk_collect_services_asset_name, VM 은 uk_collect_services_vm_name.
-- 적용: sudo -u postgres psql -d aims -v ON_ERROR_STOP=1 -f 2026-10-06_collect_services_vm.sql
-- 새 DB 는 2026-09-11_collect_services.sql 다음에 돌린다.
BEGIN;
ALTER TABLE collect_services ALTER COLUMN asset_id DROP NOT NULL;
ALTER TABLE collect_services ADD COLUMN IF NOT EXISTS tb_ns VARCHAR(200);
ALTER TABLE collect_services ADD COLUMN IF NOT EXISTS tb_infra VARCHAR(200);
ALTER TABLE collect_services ADD COLUMN IF NOT EXISTS tb_node VARCHAR(200);
ALTER TABLE collect_services DROP CONSTRAINT IF EXISTS ck_collect_services_owner;
ALTER TABLE collect_services ADD CONSTRAINT ck_collect_services_owner CHECK (
    (asset_id IS NOT NULL AND tb_ns IS NULL AND tb_infra IS NULL AND tb_node IS NULL)
    OR (asset_id IS NULL AND tb_ns IS NOT NULL AND tb_infra IS NOT NULL AND tb_node IS NOT NULL));
ALTER TABLE collect_services DROP CONSTRAINT IF EXISTS uk_collect_services_vm_name;
ALTER TABLE collect_services ADD CONSTRAINT uk_collect_services_vm_name UNIQUE (tb_ns, tb_infra, tb_node, service_name);
COMMIT;
