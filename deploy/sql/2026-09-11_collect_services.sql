-- 수집 설정 원장만 관리한다. 수집기 설정 및 Loki 데이터에는 영향을 주지 않는다.
BEGIN;
CREATE TABLE IF NOT EXISTS collect_services (
    id BIGSERIAL PRIMARY KEY,
    asset_id BIGINT NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    service_name VARCHAR(200) NOT NULL CHECK (length(trim(service_name)) > 0),
    description VARCHAR(500),
    log_enabled BOOLEAN NOT NULL DEFAULT false,
    syslog_enabled BOOLEAN NOT NULL DEFAULT false,
    trace_enabled BOOLEAN NOT NULL DEFAULT false,
    created_by VARCHAR(200),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uk_collect_services_asset_name UNIQUE (asset_id, service_name)
);
CREATE TABLE IF NOT EXISTS collect_service_log_paths (
    service_id BIGINT NOT NULL REFERENCES collect_services(id) ON DELETE CASCADE,
    path VARCHAR(500) NOT NULL,
    PRIMARY KEY (service_id, path)
);
CREATE TABLE IF NOT EXISTS collect_service_trace_ports (
    service_id BIGINT NOT NULL REFERENCES collect_services(id) ON DELETE CASCADE,
    port INTEGER NOT NULL CHECK (port BETWEEN 1 AND 65535),
    PRIMARY KEY (service_id, port)
);
COMMIT;
