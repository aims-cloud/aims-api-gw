-- 네트워크 IPAM (네트워크 구역/서브넷/IP 주소) — feat/network-ipam
-- VM PostgreSQL은 ddl-auto: validate 이므로 배포 전 수동 실행 필요:
--   psql -U aims -d aims -h localhost -f 2026-09-08_network_ipam.sql
-- (주의: 기존 읽기전용 network zone/subnet API는 DB 테이블이 없었으므로 DROP 대상 없음)

CREATE TABLE IF NOT EXISTS network_zones (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS network_zone_interfaces (
    id                   BIGSERIAL PRIMARY KEY,
    zone_id              BIGINT NOT NULL REFERENCES network_zones (id) ON DELETE CASCADE,
    deploy_zone          VARCHAR(100),
    openstack_network_id VARCHAR(100),
    network_name         VARCHAR(200),
    datacenter           VARCHAR(100),
    CONSTRAINT uq_zone_interface UNIQUE (zone_id, openstack_network_id)
);

CREATE TABLE IF NOT EXISTS network_subnets (
    id          BIGSERIAL PRIMARY KEY,
    zone_id     BIGINT NOT NULL REFERENCES network_zones (id) ON DELETE CASCADE,
    cidr        VARCHAR(50) NOT NULL,
    gateway     VARCHAR(100),
    description VARCHAR(500),
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS ip_addresses (
    id            BIGSERIAL PRIMARY KEY,
    subnet_id     BIGINT NOT NULL REFERENCES network_subnets (id) ON DELETE CASCADE,
    zone_id       BIGINT NOT NULL,
    ip_address    VARCHAR(60) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    alias         VARCHAR(100),
    description   VARCHAR(500),
    resource_type VARCHAR(30),
    resource_id   VARCHAR(100),
    resource_name VARCHAR(200),
    mac_address   VARCHAR(60),
    port_group    VARCHAR(200),
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ,
    CONSTRAINT uq_ip_subnet_addr UNIQUE (subnet_id, ip_address)
);

CREATE TABLE IF NOT EXISTS network_audit_histories (
    id           BIGSERIAL PRIMARY KEY,
    target       VARCHAR(20) NOT NULL,
    target_id    BIGINT,
    action       VARCHAR(20) NOT NULL,
    result       VARCHAR(20) NOT NULL,
    detail       VARCHAR(1000),
    performed_by VARCHAR(100),
    client_ip    VARCHAR(60),
    started_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_subnets_zone ON network_subnets (zone_id);
CREATE INDEX IF NOT EXISTS idx_ip_subnet ON ip_addresses (subnet_id);
CREATE INDEX IF NOT EXISTS idx_ip_zone ON ip_addresses (zone_id);
CREATE INDEX IF NOT EXISTS idx_audit_target ON network_audit_histories (target, target_id, started_at DESC);
