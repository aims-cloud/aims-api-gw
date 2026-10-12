package com.innogrid.aims.dashboard.entity;

import java.time.OffsetDateTime;
import com.innogrid.aims.dashboard.model.GpuDashboardThreshold;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * GPU 대시보드 임계값 — 전역 공용 단일 행(id = 1). 임계값 팝업(활성·유휴·고활용)의 "적용"이 이 행을 바꾼다.
 * 재시작해도 유지되며, 행이 없으면 서비스가 기본값을 쓴다 (deploy/sql/2026-10-06_gpu_dashboard_thresholds.sql).
 */
@Getter
@Entity
@Table(name = "gpu_dashboard_thresholds")
public class GpuDashboardThresholdSetting {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "active_gpu_utilization", nullable = false)
    private double activeGpuUtilization;

    @Column(name = "idle_gpu_utilization", nullable = false)
    private double idleGpuUtilization;

    @Column(name = "high_gpu_utilization", nullable = false)
    private double highGpuUtilization;

    @Column(name = "high_vram_utilization", nullable = false)
    private double highVramUtilization;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", length = 200)
    private String updatedBy;

    protected GpuDashboardThresholdSetting() {}

    public static GpuDashboardThresholdSetting of(GpuDashboardThreshold value, String updatedBy) {
        GpuDashboardThresholdSetting row = new GpuDashboardThresholdSetting();
        row.id = SINGLETON_ID;
        row.apply(value, updatedBy);
        return row;
    }

    public void apply(GpuDashboardThreshold value, String updatedBy) {
        this.activeGpuUtilization = value.activeGpuUtilization();
        this.idleGpuUtilization = value.idleGpuUtilization();
        this.highGpuUtilization = value.highGpuUtilization();
        this.highVramUtilization = value.highVramUtilization();
        this.updatedBy = updatedBy;
    }

    public GpuDashboardThreshold toThreshold() {
        return new GpuDashboardThreshold(activeGpuUtilization, idleGpuUtilization, highGpuUtilization, highVramUtilization);
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }
}
