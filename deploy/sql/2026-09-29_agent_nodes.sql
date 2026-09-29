-- 노드 수집 에이전트(Alloy) 관리 원장. 자산 1건당 1행.
-- 지금은 수집 대상 목록(log-targets.json) 전송 방식과 적용 상태만 쓰고, SSH·설치 컬럼은 에이전트 매니저 구현을 위해 미리 둔다.
-- 적용: sudo -u postgres psql -d aims -f 2026-09-29_agent_nodes.sql
BEGIN;
CREATE TABLE IF NOT EXISTS agent_nodes (
    asset_id BIGINT PRIMARY KEY REFERENCES assets(id) ON DELETE CASCADE,
    transport VARCHAR(16) NOT NULL DEFAULT 'NONE' CHECK (transport IN ('NONE', 'LOCAL', 'SSH')),
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
FROM assets WHERE name = 'k8s-cp'
ON CONFLICT (asset_id) DO NOTHING;
COMMIT;
