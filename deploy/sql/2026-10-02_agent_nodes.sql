-- 수집 에이전트 원장(agent_nodes) 재생성 — PULL 전송 추가 (docs/11 §4.4·§5.3).
-- 처음 만든 2026-09-29 스크립트(운영 적용 완료)를 대체한다. 기존 표를 지우고 새 정의로 다시 만들며, k8s-cp 의 LOCAL 시드도 다시 넣는다.
-- 이 표는 운영자가 시드한 전송 방식과 목록 적용 상태(해시·시각)만 담으므로 지워도 잃는 것이 없다 —
-- 적용 상태는 재적용 스케줄러(60초)가 다음 주기에 다시 채운다. collect_services 등 다른 표는 건드리지 않는다.
-- 적용: sudo -u postgres psql -d aims -f 2026-10-02_agent_nodes.sql
BEGIN;

DROP TABLE IF EXISTS agent_nodes;

CREATE TABLE agent_nodes (
    asset_id BIGINT PRIMARY KEY REFERENCES assets(id) ON DELETE CASCADE,
    -- NONE: 전송 안 함 / LOCAL: 백엔드가 같은 서버의 파일을 직접 씀 /
    -- PULL: 노드 Alloy 가 자기 instance(node_exporter address:port)로 목록 API 를 가져감 / SSH: 열거만 유지(구현 계획 없음)
    transport VARCHAR(16) NOT NULL DEFAULT 'NONE' CHECK (transport IN ('NONE', 'LOCAL', 'SSH', 'PULL')),
    -- 목록 파일 경로. LOCAL 만 쓰고 PULL 은 NULL
    targets_path VARCHAR(500),
    ssh_host VARCHAR(255),
    ssh_port INTEGER CHECK (ssh_port IS NULL OR ssh_port BETWEEN 1 AND 65535),
    ssh_user VARCHAR(100),
    ssh_secret TEXT,
    auth_type VARCHAR(16) CHECK (auth_type IS NULL OR auth_type IN ('PASSWORD', 'KEY')),
    access_status VARCHAR(16),
    access_checked_at TIMESTAMP WITH TIME ZONE,
    install_status VARCHAR(16) NOT NULL DEFAULT 'NOT_INSTALLED'
        CHECK (install_status IN ('NOT_INSTALLED', 'INSTALLING', 'INSTALLED', 'FAILED')),
    install_step VARCHAR(50),
    install_error TEXT,
    installed_at TIMESTAMP WITH TIME ZONE,
    agent_version VARCHAR(50),
    targets_hash_desired VARCHAR(64),
    targets_hash_applied VARCHAR(64),
    targets_applied_at TIMESTAMP WITH TIME ZONE,
    targets_error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_by VARCHAR(200)
);
ALTER TABLE agent_nodes OWNER TO aims;

-- k8s-cp: 백엔드와 Alloy 가 같은 서버라 로컬 파일 전송. 수동 설치된 Alloy 를 쓰므로 설치 상태는 INSTALLED 로 둔다.
INSERT INTO agent_nodes (asset_id, transport, targets_path, install_status, installed_at, agent_version, updated_by)
SELECT id, 'LOCAL', '/home/ubuntu/opt/monitoring/alloy-targets/log-targets.json', 'INSTALLED', now(), 'v1.20.0', 'deploy-sql'
FROM assets WHERE name = 'k8s-cp';

COMMIT;

-- ── PULL 노드 등록 절차 (노드마다 1회, 운영자 수동) ─────────────────────────────────────
-- 전제: 자산관리 > 물리 자산에 노드가 등록되어 있고, 그 자산에 NODE_EXPORTER 타겟(monitoring_target.address:port)이 연결되어 있다.
--       노드 Alloy 는 그 address:port 값을 NODE_INSTANCE 로 갖고 목록 API 를 부른다. 토큰·비밀값은 없다.
--
--    INSERT INTO agent_nodes (asset_id, transport, install_status, updated_by)
--    SELECT id, 'PULL', 'NOT_INSTALLED', 'deploy-sql'
--    FROM assets WHERE name = '<자산 이름>'
--    ON CONFLICT (asset_id) DO UPDATE
--      SET transport = 'PULL', targets_path = NULL, updated_at = now();
--
-- 노드 Alloy 가 처음 가져가면 targets_applied_at 이 채워지고 GET /services/agent 가 APPLIED 가 된다(deploy/alloy-node/README.md).
