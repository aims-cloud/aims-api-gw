package com.innogrid.aims.monitoring.entity;

import java.time.OffsetDateTime;

import com.innogrid.aims.infra.entity.Asset;
import jakarta.persistence.*;

/**
 * 모니터링 수집 대상 노드.
 * Prometheus가 스크랩할 exporter 엔드포인트 정보를 관리한다.
 */
@Entity
@Table(name = "monitoring_target")
public class MonitoringTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String mdcId;

    @Column(nullable = false, length = 100)
    private String hostname;

    /** 별칭 — 토폴로지 '별칭표기' 토글용 표시 이름 (미설정 시 hostname 사용) */
    @Column(length = 100)
    private String alias;

    @Column(nullable = false, length = 255)
    private String address;

    @Column(nullable = false)
    private int port;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExporterType exporterType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TargetStatus status = TargetStatus.PENDING;

    /**
     * 이 exporter 가 설치된 물리 자산.
     * 자원 탐색기 트리는 호스트↔exporter 를 hostname/IP 비교로 추정하지 않고 이 연결로만 판단한다
     * (docs/06 자원 탐색기 트리 설계 §9.4). 미연결 타겟은 트리 항목 판정에서 제외된다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id")
    private Asset asset;

    private OffsetDateTime createdAt;
    private OffsetDateTime lastSeenAt;

    protected MonitoringTarget() {}

    public MonitoringTarget(String mdcId, String hostname, String address,
                            int port, ExporterType exporterType) {
        this.mdcId = mdcId;
        this.hostname = hostname;
        this.address = address;
        this.port = port;
        this.exporterType = exporterType;
        this.createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public String getMdcId() { return mdcId; }
    public String getHostname() { return hostname; }
    public String getAddress() { return address; }
    public int getPort() { return port; }
    public ExporterType getExporterType() { return exporterType; }
    public TargetStatus getStatus() { return status; }
    public Asset getAsset() { return asset; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }

    public String getAlias() { return alias; }

    /** 별칭 또는 hostname — 토폴로지 별칭표기 토글의 표시값 */
    public String getDisplayName() {
        return alias == null || alias.isBlank() ? hostname : alias;
    }

    public void setStatus(TargetStatus status) { this.status = status; }
    public void setLastSeenAt(OffsetDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public void setAlias(String alias) { this.alias = alias; }
    public void setAsset(Asset asset) { this.asset = asset; }

    /** 이 타겟의 Prometheus scrape 주소 — Thanos 시계열의 instance 라벨 값과 동일하다 */
    public String getScrapeEndpoint() {
        return address + ":" + port;
    }

    public enum ExporterType {
        NODE_EXPORTER,
        DCGM_EXPORTER,
        IPMI_EXPORTER,
        SNMP_EXPORTER
    }

    public enum TargetStatus {
        PENDING,
        UP,
        DOWN
    }
}
