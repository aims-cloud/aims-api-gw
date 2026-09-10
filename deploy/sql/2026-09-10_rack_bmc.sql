-- 랙실장도 BMC(Redfish) 연동 (feat/rack-redfish)
-- VM PostgreSQL은 ddl-auto: validate 이므로 배포 전 수동 실행 필요:
--   psql -U aims -d aims -f 2026-09-10_rack_bmc.sql
--
-- 1) rack_units 에 BMC 엔드포인트 참조 컬럼 추가 (Redfish 하드웨어 상세조회 대상 표시)
-- 2) 샘플 랙 '2F-27-04' 시드 — 서버 6대(AIO 3 · GPU 3) + 장비(FW/Storage/NAS)
--    BMC 접속정보(URL/계정/비밀번호)는 여기에 두지 않는다. application.yml 의 BMC_* 환경변수로만 주입.
--    U 위치는 실제 도면에 맞춰 조정 가능.

-- ── 1) 컬럼 추가 ────────────────────────────────────────────
ALTER TABLE rack_units ADD COLUMN IF NOT EXISTS bmc_endpoint_id VARCHAR(50);

-- ── 2) 샘플 랙 시드 (중복 방지: 이름 기준) ──────────────────
INSERT INTO racks (mdc_id, name, total_units, power_capacity_kw,
                   category, location, floor_no, zone_name, row_no, position_no, created_at)
SELECT 'sample-mdc', '2F-27-04', 42, 0,
       '서버랙', '연구동 전산실', '2F', '27구역', '27열', '04', now()
WHERE NOT EXISTS (SELECT 1 FROM racks WHERE name = '2F-27-04');

-- 실장 장비 — rack_id 는 위에서 만든 '2F-27-04' 를 조회해 사용
INSERT INTO rack_units (rack_id, position, asset_name, asset_type, bmc_endpoint_id)
SELECT r.id, v.position, v.asset_name, v.asset_type, v.bmc_endpoint_id
FROM racks r
CROSS JOIN (VALUES
    ('U40', '방화벽',       'FIREWALL', NULL),
    ('U38', 'NAS',          'NAS',      NULL),
    ('U36', 'Storage',      'STORAGE',  NULL),
    ('U24', 'gpu-03',       'GPU_SERVER', 'gpu03'),   -- Dell IDRAC
    ('U22', 'gpu-02',       'GPU_SERVER', 'gpu02'),   -- Dell IDRAC
    ('U20', 'gpu-01',       'GPU_SERVER', 'gpu01'),   -- Lenovo XCC
    ('U12', 'aio-03',       'SERVER',     'aio03'),   -- Lenovo XCC
    ('U10', 'aio-02',       'SERVER',     'aio02'),   -- Lenovo XCC
    ('U8',  'aio-01',       'SERVER',     'aio01')    -- Lenovo XCC
) AS v(position, asset_name, asset_type, bmc_endpoint_id)
WHERE r.name = '2F-27-04'
  AND NOT EXISTS (SELECT 1 FROM rack_units u WHERE u.rack_id = r.id);
