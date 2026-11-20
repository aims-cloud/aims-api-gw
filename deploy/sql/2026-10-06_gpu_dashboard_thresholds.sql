-- GPU 대시보드 판정 임계값 — 전역 공용 단일 행(id = 1) (Confluence 2320072739 §1.5 R-18~R-21, 1단계 기반).
-- 이전에는 메모리에만 있어 재시작하면 초기화됐다. 활성 GPU 기준(active_gpu_utilization)을 함께 추가한다.
-- 판정: 활성 사용률 > active, 유휴 사용률 < idle(같으면 어느 쪽도 아님), 고활용 사용률 >= high AND VRAM >= high_vram.
-- 실행(새 백엔드 기동 전):
--   sudo -u postgres psql -d aims -v ON_ERROR_STOP=1 -f 2026-10-06_gpu_dashboard_thresholds.sql

CREATE TABLE IF NOT EXISTS gpu_dashboard_thresholds (
    id                     BIGINT           PRIMARY KEY,
    active_gpu_utilization DOUBLE PRECISION NOT NULL,
    idle_gpu_utilization   DOUBLE PRECISION NOT NULL,
    high_gpu_utilization   DOUBLE PRECISION NOT NULL,
    high_vram_utilization  DOUBLE PRECISION NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_by             VARCHAR(200),
    CONSTRAINT ck_gpu_dashboard_thresholds_singleton CHECK (id = 1),
    CONSTRAINT ck_gpu_dashboard_thresholds_range CHECK (
        active_gpu_utilization BETWEEN 0 AND 100 AND idle_gpu_utilization BETWEEN 0 AND 100
        AND high_gpu_utilization BETWEEN 0 AND 100 AND high_vram_utilization BETWEEN 0 AND 100
        AND idle_gpu_utilization <= high_gpu_utilization)
);

-- 기본값 행(피그마 기본 문구). 이미 있으면 그대로 둔다.
INSERT INTO gpu_dashboard_thresholds (id, active_gpu_utilization, idle_gpu_utilization, high_gpu_utilization,
                                      high_vram_utilization, updated_by)
SELECT 1, 10, 10, 60, 60, 'deploy-sql'
WHERE NOT EXISTS (SELECT 1 FROM gpu_dashboard_thresholds WHERE id = 1);

ALTER TABLE gpu_dashboard_thresholds OWNER TO aims;
