-- 스케줄러 확장 — 리소스 종류별 배치 정책·워크로드 상태 전이 (feat/scheduler-core 단계 2)
-- VM PostgreSQL 은 ddl-auto: update 라 Hibernate 가 자동 반영하지만,
-- 스키마를 의도대로 고정하기 위해 스크립트로도 남긴다.
--   psql -U aims -d aims -f 2026-08-31_scheduler_extension.sql
--
-- 주의: 모든 컬럼은 nullable 이다. workloads 에 기존 행이 있어
--       NOT NULL 컬럼을 추가하면 ddl-auto=update 가 실패한다.

ALTER TABLE workloads ADD COLUMN IF NOT EXISTS resource_type VARCHAR(20);
ALTER TABLE workloads ADD COLUMN IF NOT EXISTS data_mdc_id   VARCHAR(50);

ALTER TABLE scheduling_policies ADD COLUMN IF NOT EXISTS resource_type               VARCHAR(20);
ALTER TABLE scheduling_policies ADD COLUMN IF NOT EXISTS default_engine              VARCHAR(30);
ALTER TABLE scheduling_policies ADD COLUMN IF NOT EXISTS warning_temperature_celsius DOUBLE PRECISION;

-- Hibernate 는 @Enumerated(EnumType.STRING) 컬럼에 CHECK 제약을 만든다.
-- 자동 생성되지 않은 환경을 위해 같은 제약을 명시한다.
-- ※ 리소스 종류를 추가할 때는 아래 두 제약을 DROP 후 새 값 목록으로 다시 만들어야 한다.
--   (ddl-auto=update 는 기존 CHECK 제약을 수정하지 않는다 — 개발순서.md 부록 A)
ALTER TABLE workloads DROP CONSTRAINT IF EXISTS workloads_resource_type_check;
ALTER TABLE workloads ADD CONSTRAINT workloads_resource_type_check
    CHECK (resource_type IS NULL OR resource_type IN ('VM', 'GPU', 'NPU', 'CONTAINER'));

ALTER TABLE scheduling_policies DROP CONSTRAINT IF EXISTS scheduling_policies_resource_type_check;
ALTER TABLE scheduling_policies ADD CONSTRAINT scheduling_policies_resource_type_check
    CHECK (resource_type IS NULL OR resource_type IN ('VM', 'GPU', 'NPU', 'CONTAINER'));

-- 활성 정책은 리소스 종류별 1개 + 공통(NULL) 1개까지만 존재해야 한다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_scheduling_policies_active_type
    ON scheduling_policies (resource_type) WHERE active;
CREATE UNIQUE INDEX IF NOT EXISTS uq_scheduling_policies_active_common
    ON scheduling_policies ((1)) WHERE active AND resource_type IS NULL;
