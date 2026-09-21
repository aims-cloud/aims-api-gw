-- 워크로드 요구 대역폭 — 링크 비용의 부족분 항 (feat/link-cost-bandwidth)
--
-- MDC 링크 원장은 대역폭을 저장해 두고도 점수에 쓰지 않았다. 워크로드 쪽에 "얼마가 필요한가"가
-- 없어 비교 대상이 없었기 때문이다. 이 컬럼이 그 짝이며, 링크 대역폭이 이 값에 못 미치는 만큼
-- 링크 점수를 깎는다(제약이 아니라 점수라 부족해도 배치는 된다).
--
-- ddl-auto 에 따라 실행 필요 여부가 다르다:
--   * 기본 프로파일(application.yml)은 validate — 배포 전에 이 스크립트를 먼저 실행해야 한다.
--   * 배포 VM 은 환경파일(SPRING_JPA_HIBERNATE_DDL_AUTO=update)이라 Hibernate 가 자동 추가하지만,
--     스키마를 의도대로 고정하기 위해 스크립트로 남긴다.
--   psql -U aims -d aims -f 2026-09-17_workload_required_bandwidth.sql
--
-- 기존 행은 NULL 로 남는다. NULL 이면 부족분 항이 0 이라 예전과 같은 점수가 나온다.

ALTER TABLE workloads ADD COLUMN IF NOT EXISTS required_bandwidth_mbps DOUBLE PRECISION;
