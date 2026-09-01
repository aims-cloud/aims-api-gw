package com.innogrid.aims.mock;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.dashboard.model.GpuDashboardDataset;
import com.innogrid.aims.dashboard.model.GpuDashboardThreshold;
import com.innogrid.aims.dashboard.model.GpuDeviceMetrics;
import com.innogrid.aims.dashboard.model.GpuMetricHistory;
import com.innogrid.aims.monitoring.model.ResourceExplorerTreeDataset;
import com.innogrid.aims.monitoring.model.ResourceExplorerTreeDataset.NodeInfo;
import com.innogrid.aims.monitoring.model.ResourceExplorerTreeDataset.RoleInfo;
import com.innogrid.aims.monitoring.model.ResourceExplorerTreeDataset.VmInfo;
import org.springframework.stereotype.Component;

/**
 * Frontend 화면 설계를 위한 목 데이터 제공.
 * 실제 구현이 완료되면 이 클래스를 제거한다.
 */
@Component
public class MockDataProvider {

    // ── 대시보드 ────────────────────────────────────────────────
    // TODO:: 실제 Thanos 연동 시 GPU Mock 데이터와 이 메서드 제거
    public GpuDashboardDataset getGpuDashboardDataset() {
        return new GpuDashboardDataset(
                new GpuDashboardThreshold(10.0, 60.0, 60.0),
                List.of(
                        gpu("gpu-01", "0", "GPU-A001", "gpu-node-01", "AI_TRAINING", "seoul-a",
                                "vm-01", "training-vm-01", "projectA", "credentialA", "NVIDIA A100",
                                "PASSTHROUGH", 92.0, 38584.0, 40960.0, 40120.0),
                        gpu("gpu-02", "1", "GPU-A002", "gpu-node-01", "AI_TRAINING", "seoul-a",
                                "vm-01", "training-vm-01", "projectA", "credentialA", "NVIDIA A100",
                                "MIG", 86.0, 35220.0, 40960.0, 37110.0),
                        gpu("gpu-03", "0", "GPU-H001", "gpu-node-02", "INFERENCE", "seoul-b",
                                "vm-02", "inference-vm-01", "projectA", "credentialB", "NVIDIA H100",
                                "VGPU", 78.0, 61440.0, 81920.0, 70120.0),
                        gpu("gpu-04", "1", "GPU-H002", "gpu-node-02", "INFERENCE", "seoul-b",
                                "vm-03", "inference-vm-02", "projectB", "credentialB", "NVIDIA H100",
                                "PASSTHROUGH", 64.0, 49152.0, 81920.0, 55800.0),
                        gpu("gpu-05", "0", "GPU-L001", "gpu-node-03", "DEVELOPMENT", "daejeon-a",
                                "vm-04", "dev-vm-01", "projectB", "credentialC", "NVIDIA L40S",
                                "VGPU", 55.0, 24576.0, 49152.0, 30100.0),
                        gpu("gpu-06", "1", "GPU-L002", "gpu-node-03", "DEVELOPMENT", "daejeon-a",
                                "vm-05", "dev-vm-02", "projectC", "credentialC", "NVIDIA L40S",
                                "MIG", 38.0, 16384.0, 49152.0, 20500.0),
                        gpu("gpu-07", "0", "GPU-T001", "gpu-node-04", "BATCH", "busan-a",
                                "vm-06", "batch-vm-01", "projectC", "credentialD", "NVIDIA T4",
                                "PASSTHROUGH", 8.0, 2048.0, 16384.0, 4100.0),
                        gpu("gpu-08", "1", "GPU-T002", "gpu-node-04", "BATCH", "busan-a",
                                null, null, null, null, "NVIDIA T4", "UNASSIGNED",
                                0.0, 0.0, 16384.0, 0.0)
                ));
    }

    // TODO:: 실제 Thanos 연동 시 GPU Mock 데이터 생성 헬퍼 제거
    private GpuDeviceMetrics gpu(String gpuId, String gpuIndex, String uuid, String hostname, String role,
                                String deploymentId, String vmId, String vmName, String projectName,
                                String credentialName, String gpuType, String allocationType,
                                double gpuUtilization, double vramUsedMb, double vramTotalMb, double peakVramMb) {
        return new GpuDeviceMetrics(gpuId, gpuIndex, uuid, hostname, role, deploymentId, vmId, vmName,
                projectName, credentialName, gpuType, allocationType, gpuUtilization, vramUsedMb,
                vramTotalMb, peakVramMb, gpuHistory(gpuUtilization, vramUsedMb));
    }

    // ── 자원 탐색기 트리 ────────────────────────────────────────
    // TODO:: 자원 관리 데이터(역할·노드·VM 원장) 연동 시 트리 Mock 데이터와 이 메서드 제거
    public ResourceExplorerTreeDataset getResourceExplorerTreeDataset() {
        return new ResourceExplorerTreeDataset(
                List.of(new RoleInfo("ADMIN", "역할 A")),
                List.of(new NodeInfo("node-01", "node 01", "ADMIN", "Openstack-GPU", "RUNNING", List.of("gpu-01", "gpu-02"))),
                List.of(new VmInfo("vm-01", "vm 01", "ADMIN", "Openstack-GPU", "RUNNING", List.of("gpu-01", "gpu-02"))));
    }

    // TODO:: 실제 Thanos 연동 시 GPU Mock 시계열 생성 헬퍼 제거
    private List<GpuMetricHistory> gpuHistory(double gpuUtilization, double vramUsedMb) {
        return List.of(
                new GpuMetricHistory(OffsetDateTime.parse("2026-08-27T09:00:00Z"), gpuUtilization * 0.65, vramUsedMb * 0.70),
                new GpuMetricHistory(OffsetDateTime.parse("2026-08-27T10:00:00Z"), gpuUtilization * 0.78, vramUsedMb * 0.82),
                new GpuMetricHistory(OffsetDateTime.parse("2026-08-27T11:00:00Z"), gpuUtilization * 0.90, vramUsedMb * 0.93),
                new GpuMetricHistory(OffsetDateTime.parse("2026-08-27T12:00:00Z"), gpuUtilization, vramUsedMb)
        );
    }
}
