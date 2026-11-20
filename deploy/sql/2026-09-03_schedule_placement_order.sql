-- 스케줄 배치 순서 선택지 (feat/consolidation-order-and-margin, 단계 16)
-- 전력 통합은 큰 워크로드부터 채워야 조각화가 준다. 같은 우선순위 안에서만 크기 우선.
-- 배포 VM 은 환경파일(SPRING_JPA_HIBERNATE_DDL_AUTO=update)로 자동 반영, validate 환경은 먼저 실행.
--   psql -U aims -d aims -f 2026-09-03_schedule_placement_order.sql
-- 기존 행은 NULL = PRIORITY_THEN_AGE(현행) 로 해석된다.

ALTER TABLE schedules ADD COLUMN IF NOT EXISTS placement_order VARCHAR(30);

-- Hibernate 가 @Enumerated(STRING) 컬럼에 만드는 CHECK 와 같은 제약(값 추가 시 DROP 후 재생성)
ALTER TABLE schedules DROP CONSTRAINT IF EXISTS schedules_placement_order_check;
ALTER TABLE schedules ADD CONSTRAINT schedules_placement_order_check
    CHECK (placement_order IS NULL OR placement_order IN ('PRIORITY_THEN_AGE', 'PRIORITY_THEN_SIZE'));
