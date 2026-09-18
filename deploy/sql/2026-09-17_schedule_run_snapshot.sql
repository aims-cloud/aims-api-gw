-- 실행 이력의 클러스터 스냅샷 — 배치 전후 상태 (feat/run-cluster-snapshot)
--
-- 스케줄 실행 1회가 클러스터를 무엇으로 바꿨는지 이력에 남는 값이 없었다. 실행 뒤에 남는 것은 건수
-- 셋(대상·성공·실패)뿐이라 "몇 대가 깨어났고 회수 가능 전력이 얼마나 줄었는가" 를 나중에 물을 수 없었다.
--
-- 축을 둘로 나눈다. 노드 수·수집 제외 수·계측 노드 수·실측 전력 합은 배치를 얹어도 다시 재지 않으므로
-- 전·후가 없고 한 번만 적는다. 가동 노드 수·회수 가능 전력·평균 온도는 예약을 반영한 실효값이라 배치로
-- 움직이므로 전·후를 함께 적는다. 여기서 "후" 는 재측정이 아니라 이번 실행의 예약을 반영한 예상치다.
--
-- 이 값들은 순간 전력(W)이지 에너지(Wh)가 아니다. 회차별 차를 그대로 더해도 절감량이 되지 않는다 —
-- 각 값이 얼마 동안 유효했는지를 이 표가 알지 못하기 때문이다. 자동 실행은 대상이 0건이면 행 자체를
-- 남기지 않으므로 한가했던 구간도 시계열에서 빠진다. 한 회차를 들여다보는 진단용으로 쓴다.
--
-- 이 행들은 실행 이력 정리(run-retention-days, 기본 90일)에 함께 지워진다. 배치 결정 이력과 달리
-- 보존되지 않으므로, 더 긴 구간의 집계가 필요하면 지워지기 전에 따로 적재해야 한다.
--
-- 이 스크립트는 ddl-auto 값과 무관하게 실행해야 한다.
--   * 기본 프로파일(application.yml)은 validate — 배포 전에 먼저 실행한다.
--   * ddl-auto=update 인 서버도 마찬가지다. 열은 Hibernate 가 더해 주지만 **상태 값 CHECK 는 다르다**.
--     Hibernate 는 열거 열을 처음 만들 때 그 시점의 값 목록으로 CHECK 를 자동 생성하고, 그 뒤에는
--     값이 늘어도 고치지 않는다. 그래서 이미 옛 목록으로 제약이 걸린 서버에서는 새 값을 쓰는 실행이
--     통째로 실패한다(2026-09-18 개발 서버 실측 — 대상 0건 즉시 실행이 500).
--     아래 DROP + ADD 가 그 제약을 바로잡는 유일한 수단이다.
--   psql -U aims -d aims -v ON_ERROR_STOP=1 -f 2026-09-17_schedule_run_snapshot.sql
--
-- 기존 행은 전부 NULL 로 남는다. 응답은 그 행의 스냅샷을 통째로 비워 내보내므로, 옛 실행이 "값 0" 으로
-- 읽히지 않는다. 컬럼을 전부 nullable 로 두는 이유이기도 하다 — 행이 있는 표에 NOT NULL 을 더하면
-- ddl-auto=update 가 실패한다.

ALTER TABLE schedule_runs ADD COLUMN IF NOT EXISTS snapshot_status                   VARCHAR(20);
-- 움직이는 축 셋을 정의한 설정 노브(가동 문턱·유휴 비율·유휴 역산 스위치·열관성 계수)의 지문.
-- 이 값이 다른 구간은 같은 열이라도 숫자의 정의가 달라 한 축으로 묶어 집계하면 안 된다.
ALTER TABLE schedule_runs ADD COLUMN IF NOT EXISTS snapshot_knobs_digest             VARCHAR(12);
ALTER TABLE schedule_runs ADD COLUMN IF NOT EXISTS snapshot_node_count               INTEGER;
ALTER TABLE schedule_runs ADD COLUMN IF NOT EXISTS snapshot_excluded_count           INTEGER;
ALTER TABLE schedule_runs ADD COLUMN IF NOT EXISTS snapshot_measured_nodes           INTEGER;
ALTER TABLE schedule_runs ADD COLUMN IF NOT EXISTS snapshot_measured_power_watts     DOUBLE PRECISION;
ALTER TABLE schedule_runs ADD COLUMN IF NOT EXISTS snapshot_active_nodes_before      INTEGER;
ALTER TABLE schedule_runs ADD COLUMN IF NOT EXISTS snapshot_active_nodes_after       INTEGER;
ALTER TABLE schedule_runs ADD COLUMN IF NOT EXISTS snapshot_reclaimable_watts_before DOUBLE PRECISION;
ALTER TABLE schedule_runs ADD COLUMN IF NOT EXISTS snapshot_reclaimable_watts_after  DOUBLE PRECISION;
ALTER TABLE schedule_runs ADD COLUMN IF NOT EXISTS snapshot_avg_temperature_before   DOUBLE PRECISION;
ALTER TABLE schedule_runs ADD COLUMN IF NOT EXISTS snapshot_avg_temperature_after    DOUBLE PRECISION;

-- 상태 값은 다섯 가지다. ddl-auto 는 CHECK 를 만들지 않으므로 스크립트에서 건다.
-- NO_TARGET(배치할 것이 없어 평범하게 끝남)과 NO_NODES(노드를 한 대도 못 모은 장애)를 가른다 —
-- 한 값으로 뭉치면 어느 쪽이 쌓이고 있는지 이 표만으로 알 수 없다.
ALTER TABLE schedule_runs DROP CONSTRAINT IF EXISTS schedule_runs_snapshot_status_check;
ALTER TABLE schedule_runs ADD CONSTRAINT schedule_runs_snapshot_status_check
    CHECK (snapshot_status IS NULL OR snapshot_status IN ('OK', 'BEFORE_ONLY', 'NO_TARGET', 'NO_NODES', 'FAILED'));
