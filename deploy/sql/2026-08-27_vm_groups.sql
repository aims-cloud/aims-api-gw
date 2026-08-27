-- 가상서버 그룹 테이블 (feat/vm-group)
-- VM PostgreSQL은 ddl-auto: validate 이므로 배포 전 수동 실행 필요:
--   psql -U aims -d aims -f 2026-08-27_vm_groups.sql

CREATE TABLE IF NOT EXISTS vm_groups (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),
    deploy_zone VARCHAR(100),
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS vm_group_members (
    vm_group_id     BIGINT NOT NULL REFERENCES vm_groups (id) ON DELETE CASCADE,
    connection_name VARCHAR(100) NOT NULL,
    vm_name         VARCHAR(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS vm_group_action_histories (
    id           BIGSERIAL PRIMARY KEY,
    vm_group_id  BIGINT NOT NULL,
    action       VARCHAR(20) NOT NULL,
    result       VARCHAR(20) NOT NULL,
    detail       VARCHAR(1000),
    performed_by VARCHAR(100),
    created_at   TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_vm_group_action_histories_group
    ON vm_group_action_histories (vm_group_id, created_at DESC);
