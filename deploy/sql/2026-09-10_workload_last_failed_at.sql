-- 워크로드 마지막 실패 시각 — 재시도 백오프 (fix/schedule-retry-and-partial-run)
--
-- 재시도를 횟수로만 막으면 5분 주기 스케줄이 25분에 다섯 번을 전부 태운다. 실패 원인 중
-- 저절로 회복되는 것(냉각 대기 10분, 메트릭 지연 허용 10분)이 있어, 그보다 짧은 간격의
-- 재시도는 회복 전에 기회를 소모한다. 이 컬럼으로 "마지막 실패로부터 얼마나 지났는가"를
-- 후보 질의가 함께 보게 한다. 기본 간격은 15분이며 설정으로 바꾼다
-- (aims.scheduler.retry-backoff-minutes, 0 이하면 간격을 보지 않는다).
--
-- ddl-auto 에 따라 실행 필요 여부가 다르다:
--   * 기본 프로파일(application.yml)은 validate — 배포 전에 이 스크립트를 먼저 실행해야 한다.
--   * 배포 VM 은 환경파일(SPRING_JPA_HIBERNATE_DDL_AUTO=update)이라 Hibernate 가 자동 추가하지만,
--     스키마를 의도대로 고정하기 위해 스크립트로 남긴다.
--   psql -U aims -d aims -f 2026-09-10_workload_last_failed_at.sql
--
-- 기존 행은 NULL 로 남는다. NULL 이면 간격 제한 없이 대상이 되므로, 이 변경 이전에 실패한
-- 워크로드를 새 규칙으로 소급해 묶어 두지 않는다.

ALTER TABLE workloads ADD COLUMN IF NOT EXISTS last_failed_at TIMESTAMPTZ;
