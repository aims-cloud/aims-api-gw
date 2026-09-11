package com.innogrid.aims.monitoring.entity;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceRequest;
import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** 수집 희망 설정 원장. 저장/삭제는 수집기나 Loki 데이터를 변경하지 않는다. */
@Entity
@Getter
@Table(name = "collect_services", uniqueConstraints =
        @UniqueConstraint(name = "uk_collect_services_asset_name", columnNames = {"asset_id", "service_name"}))
public class CollectService {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Asset asset;
    @Column(name = "service_name", nullable = false, length = 200)
    private String serviceName;
    @Column(length = 500)
    private String description;
    @Column(name = "log_enabled", nullable = false)
    private boolean logEnabled;
    @Column(name = "syslog_enabled", nullable = false)
    private boolean syslogEnabled;
    @Column(name = "trace_enabled", nullable = false)
    private boolean traceEnabled;
    @ElementCollection
    @CollectionTable(name = "collect_service_log_paths", joinColumns = @JoinColumn(name = "service_id"),
            uniqueConstraints = @UniqueConstraint(columnNames = {"service_id", "path"}))
    @Column(name = "path", nullable = false, length = 500)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Set<String> logPaths = new LinkedHashSet<>();
    @ElementCollection
    @CollectionTable(name = "collect_service_trace_ports", joinColumns = @JoinColumn(name = "service_id"),
            uniqueConstraints = @UniqueConstraint(columnNames = {"service_id", "port"}))
    @Column(name = "port", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Set<Integer> tracePorts = new LinkedHashSet<>();
    @Column(name = "created_by", length = 200)
    private String createdBy;
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected CollectService() {}

    public CollectService(Asset asset, String createdBy) {
        this.asset = asset;
        this.createdBy = createdBy;
    }

    public void update(CollectServiceRequest request) {
        serviceName = request.serviceName().trim();
        description = request.description();
        CollectServiceRequest.LogCollection logs = request.logCollection();
        CollectServiceRequest.TraceCollection traces = request.traceCollection();
        logEnabled = logs != null && logs.enabled();
        syslogEnabled = logs != null && logs.syslogEnabled();
        traceEnabled = traces != null && traces.enabled();
        logPaths.clear();
        if (logs != null) logPaths.addAll(logs.logPaths());
        tracePorts.clear();
        if (traces != null) tracePorts.addAll(traces.ports());
    }

    @PrePersist
    void created() { createdAt = updatedAt = OffsetDateTime.now(); }
    @PreUpdate
    void updated() { updatedAt = OffsetDateTime.now(); }
}
