package com.innogrid.aims.dashboard.model;

import java.util.List;

/**
 * GPU 할당 단위 1개의 화면용 값 — PassThrough·vGPU 는 물리 GPU 1장, MIG 는 인스턴스 1개 (Confluence 2320072739 §3 B-1).
 *
 * collecting=false 는 등록 노드에서 최근 하루 안에 보였으나 조회 기간에 표본이 없는 GPU 다(행은 남기고 값은 의미 없음).
 * 이런 행은 사용률 평균·판정·순위에서 빠지고 개수에는 들어간다. 온도·전력은 dcgm_exporter 가 주지 않으면 null.
 */
public record GpuDeviceMetrics(
        String gpuId,
        String gpuIndex,
        String uuid,
        String hostname,
        String role,
        String deploymentId,
        String vmId,
        String vmName,
        String projectName,
        String credentialName,
        String gpuType,
        String allocationType,
        double gpuUtilization,
        double vramUsedMb,
        double vramTotalMb,
        double peakVramMb,
        List<GpuMetricHistory> history,
        String name,
        Double temperatureC,
        Double powerWatts,
        boolean collecting) {

    public GpuDeviceMetrics {
        history = history == null ? List.of() : history;
        name = name == null ? gpuId : name;
    }

    /** mock 데이터용 — 이름은 gpuId, 온도·전력 없음, 수집 중 */
    public GpuDeviceMetrics(String gpuId, String gpuIndex, String uuid, String hostname, String role, String deploymentId,
                            String vmId, String vmName, String projectName, String credentialName, String gpuType,
                            String allocationType, double gpuUtilization, double vramUsedMb, double vramTotalMb,
                            double peakVramMb, List<GpuMetricHistory> history) {
        this(gpuId, gpuIndex, uuid, hostname, role, deploymentId, vmId, vmName, projectName, credentialName, gpuType,
                allocationType, gpuUtilization, vramUsedMb, vramTotalMb, peakVramMb, history, gpuId, null, null, true);
    }

    public double vramUtilization() {
        return vramTotalMb == 0 ? 0.0 : vramUsedMb / vramTotalMb * 100.0;
    }
}
