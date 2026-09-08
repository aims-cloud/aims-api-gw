-- 가상서버 그룹 OpenStack 재설계 (feat/vm-group-openstack)
-- 기존 vm_groups/vm_group_members/vm_group_action_histories 를 새 스키마로 교체한다.
-- VM PostgreSQL은 ddl-auto: validate 이므로 배포 전 수동 실행 필요:
--   psql -U aims -d aims -f 2026-09-08_vm_group_openstack.sql

-- 기존 테이블 제거 (CB-Spider 기반 구조 폐기)
DROP TABLE IF EXISTS vm_group_action_histories;
DROP TABLE IF EXISTS vm_group_members;
DROP TABLE IF EXISTS vm_group_connections;
DROP TABLE IF EXISTS vm_groups;

CREATE TABLE vm_groups (
    id          BIGSERIAL PRIMARY KEY,
    deploy_zone VARCHAR(100) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_by  VARCHAR(100),
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ
);

CREATE TABLE vm_group_members (
    id          BIGSERIAL PRIMARY KEY,
    vm_group_id BIGINT NOT NULL REFERENCES vm_groups (id) ON DELETE CASCADE,
    server_id   VARCHAR(100) NOT NULL,
    vm_name     VARCHAR(200),
    host        VARCHAR(100),
    ip          VARCHAR(100),
    created_at  TIMESTAMPTZ,
    CONSTRAINT uq_vm_group_member UNIQUE (vm_group_id, server_id)
);

CREATE TABLE vm_group_connections (
    id               BIGSERIAL PRIMARY KEY,
    vm_group_id      BIGINT NOT NULL REFERENCES vm_groups (id) ON DELETE CASCADE,
    source_server_id VARCHAR(100) NOT NULL,
    source_endpoint  VARCHAR(100),
    target_server_id VARCHAR(100) NOT NULL,
    target_endpoint  VARCHAR(100),
    created_at       TIMESTAMPTZ
);

CREATE TABLE vm_group_action_histories (
    id           BIGSERIAL PRIMARY KEY,
    vm_group_id  BIGINT NOT NULL,
    target       VARCHAR(20) NOT NULL,
    action       VARCHAR(30) NOT NULL,
    result       VARCHAR(20) NOT NULL,
    detail       VARCHAR(1000),
    performed_by VARCHAR(100),
    client_ip    VARCHAR(60),
    started_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_vm_group_members_group ON vm_group_members (vm_group_id);
CREATE INDEX idx_vm_group_connections_group ON vm_group_connections (vm_group_id);
CREATE INDEX idx_vm_group_histories_group ON vm_group_action_histories (vm_group_id, started_at DESC);
