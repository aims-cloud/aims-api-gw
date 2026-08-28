package com.innogrid.aims.mock;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.dashboard.model.GpuDashboardDataset;
import com.innogrid.aims.dashboard.model.GpuDashboardThreshold;
import com.innogrid.aims.dashboard.model.GpuDeviceMetrics;
import com.innogrid.aims.dashboard.model.GpuMetricHistory;
import org.springframework.stereotype.Component;

/**
 * Frontend 화면 설계를 위한 목 데이터 제공.
 * 실제 구현이 완료되면 이 클래스를 제거한다.
 */
@Component
public class MockDataProvider {

    // ── 빌링 ────────────────────────────────────────────────────

    public List<Map<String, Object>> getCharges() {
        return List.of(
                Map.of("chargeId", 1, "userId", 1, "userName", "관리자", "mdcId", "mdc-01",
                        "period", "2026-07", "totalAmount", 1250000, "status", "BILLED",
                        "details", List.of(
                                Map.of("resourceType", "cpu", "usage", 720.5, "unitPrice", 500, "amount", 360250),
                                Map.of("resourceType", "memory", "usage", 1024.0, "unitPrice", 300, "amount", 307200),
                                Map.of("resourceType", "storage", "usage", 5120.0, "unitPrice", 50, "amount", 256000),
                                Map.of("resourceType", "gpu", "usage", 168.0, "unitPrice", 1500, "amount", 252000)
                        )),
                Map.of("chargeId", 2, "userId", 2, "userName", "홍길동", "mdcId", "mdc-02",
                        "period", "2026-07", "totalAmount", 830000, "status", "BILLED",
                        "details", List.of(
                                Map.of("resourceType", "cpu", "usage", 480.0, "unitPrice", 500, "amount", 240000),
                                Map.of("resourceType", "memory", "usage", 768.0, "unitPrice", 300, "amount", 230400),
                                Map.of("resourceType", "storage", "usage", 3072.0, "unitPrice", 50, "amount", 153600)
                        ))
        );
    }

    public List<Map<String, Object>> getUsageCosts() {
        return List.of(
                Map.of("month", "2026-05", "mdcId", "mdc-01", "totalCost", 1180000, "publicCloudCost", 320000),
                Map.of("month", "2026-06", "mdcId", "mdc-01", "totalCost", 1220000, "publicCloudCost", 350000),
                Map.of("month", "2026-07", "mdcId", "mdc-01", "totalCost", 1250000, "publicCloudCost", 380000),
                Map.of("month", "2026-05", "mdcId", "mdc-02", "totalCost", 780000, "publicCloudCost", 0),
                Map.of("month", "2026-06", "mdcId", "mdc-02", "totalCost", 810000, "publicCloudCost", 0),
                Map.of("month", "2026-07", "mdcId", "mdc-02", "totalCost", 830000, "publicCloudCost", 0)
        );
    }

    // ── 최적화 진단 ─────────────────────────────────────────────

    public Map<String, Object> getCostOptimization() {
        return Map.of(
                "totalSavingPotential", 285000,
                "recommendations", List.of(
                        Map.of("type", "VM_RIGHTSIZING", "target", "mdc-01/vm-03",
                                "currentSpec", "8vCPU/32GB", "recommendedSpec", "4vCPU/16GB",
                                "monthlySaving", 125000, "reason", "평균 CPU 사용률 12%, 메모리 사용률 18%"),
                        Map.of("type", "IDLE_RESOURCE", "target", "mdc-02/vm-07",
                                "currentSpec", "4vCPU/16GB", "recommendedSpec", "삭제 권고",
                                "monthlySaving", 80000, "reason", "30일간 CPU 사용률 0.5% 미만"),
                        Map.of("type", "CLOUD_BURSTING", "target", "mdc-01",
                                "currentSpec", "온프레미스", "recommendedSpec", "AWS t3.medium 3대",
                                "monthlySaving", 80000, "reason", "피크 시간대 퍼블릭 클라우드가 30% 저렴")
                ));
    }

    public Map<String, Object> getVmRightsizing() {
        return Map.of("vmAnalysis", List.of(
                Map.of("vmId", "vm-01", "mdcId", "mdc-01", "name", "web-server-01",
                        "currentCpu", 8, "currentMemGb", 32,
                        "avgCpuUsage", 45.2, "avgMemUsage", 62.8,
                        "recommendation", "적정", "status", "OK"),
                Map.of("vmId", "vm-03", "mdcId", "mdc-01", "name", "batch-server-01",
                        "currentCpu", 8, "currentMemGb", 32,
                        "avgCpuUsage", 12.1, "avgMemUsage", 18.5,
                        "recommendation", "4vCPU/16GB로 축소", "status", "OVERSIZED"),
                Map.of("vmId", "vm-07", "mdcId", "mdc-02", "name", "test-server-03",
                        "currentCpu", 4, "currentMemGb", 16,
                        "avgCpuUsage", 0.3, "avgMemUsage", 2.1,
                        "recommendation", "삭제 권고", "status", "IDLE")
        ));
    }

    // ── 대시보드 ────────────────────────────────────────────────

    public Map<String, Object> getInfraDashboard() {
        return Map.of(
                "totalMdcCount", 3,
                "totalNodeCount", 24,
                "totalVmCount", 87,
                "totalGpuCount", 8,
                "mdcSummary", List.of(
                        Map.of("mdcId", "mdc-01", "name", "서울 MDC", "location", "서울 강남",
                                "nodeCount", 10, "vmCount", 42, "gpuCount", 4,
                                "avgCpuUsage", 68.5, "avgMemUsage", 72.1, "status", "NORMAL"),
                        Map.of("mdcId", "mdc-02", "name", "대전 MDC", "location", "대전 유성",
                                "nodeCount", 8, "vmCount", 30, "gpuCount", 2,
                                "avgCpuUsage", 45.2, "avgMemUsage", 55.8, "status", "NORMAL"),
                        Map.of("mdcId", "mdc-03", "name", "부산 MDC", "location", "부산 해운대",
                                "nodeCount", 6, "vmCount", 15, "gpuCount", 2,
                                "avgCpuUsage", 82.3, "avgMemUsage", 88.5, "status", "WARNING")
                ),
                "alerts", List.of(
                        Map.of("level", "WARNING", "message", "부산 MDC CPU 사용률 82% 초과", "time", "2026-08-11T14:30:00Z"),
                        Map.of("level", "INFO", "message", "서울 MDC GPU-2 온도 75°C", "time", "2026-08-11T14:25:00Z")
                )
        );
    }

    public Map<String, Object> getNodeStatusDashboard() {
        return Map.of("nodes", List.of(
                Map.of("nodeId", "node-01", "mdcId", "mdc-01", "hostname", "sv-seoul-01",
                        "cpuUsage", 72.5, "memUsage", 85.3, "diskUsage", 45.2,
                        "status", "UP", "os", "Ubuntu 22.04", "uptime", "45d 12h"),
                Map.of("nodeId", "node-02", "mdcId", "mdc-01", "hostname", "sv-seoul-02",
                        "cpuUsage", 55.1, "memUsage", 63.7, "diskUsage", 38.9,
                        "status", "UP", "os", "Ubuntu 22.04", "uptime", "45d 12h"),
                Map.of("nodeId", "node-03", "mdcId", "mdc-01", "hostname", "sv-seoul-gpu-01",
                        "cpuUsage", 88.2, "memUsage", 91.5, "diskUsage", 52.1,
                        "status", "WARNING", "os", "Ubuntu 22.04", "uptime", "30d 8h"),
                Map.of("nodeId", "node-04", "mdcId", "mdc-02", "hostname", "sv-daejeon-01",
                        "cpuUsage", 32.8, "memUsage", 41.2, "diskUsage", 28.5,
                        "status", "UP", "os", "Rocky Linux 9.2", "uptime", "60d 3h")
        ));
    }

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

    // TODO:: 실제 Thanos 연동 시 GPU Mock 시계열 생성 헬퍼 제거
    private List<GpuMetricHistory> gpuHistory(double gpuUtilization, double vramUsedMb) {
        return List.of(
                new GpuMetricHistory(OffsetDateTime.parse("2026-08-27T09:00:00Z"), gpuUtilization * 0.65, vramUsedMb * 0.70),
                new GpuMetricHistory(OffsetDateTime.parse("2026-08-27T10:00:00Z"), gpuUtilization * 0.78, vramUsedMb * 0.82),
                new GpuMetricHistory(OffsetDateTime.parse("2026-08-27T11:00:00Z"), gpuUtilization * 0.90, vramUsedMb * 0.93),
                new GpuMetricHistory(OffsetDateTime.parse("2026-08-27T12:00:00Z"), gpuUtilization, vramUsedMb)
        );
    }

    public Map<String, Object> getStorageDashboard() {
        return Map.of("storage", List.of(
                Map.of("mdcId", "mdc-01", "name", "서울 MDC",
                        "totalTb", 100, "usedTb", 62.5, "usagePercent", 62.5,
                        "volumes", 45, "disks", 24, "diskHealthy", 23, "diskWarning", 1),
                Map.of("mdcId", "mdc-02", "name", "대전 MDC",
                        "totalTb", 50, "usedTb", 28.0, "usagePercent", 56.0,
                        "volumes", 30, "disks", 12, "diskHealthy", 12, "diskWarning", 0)
        ));
    }

    public Map<String, Object> getVmStatusDashboard() {
        return Map.of(
                "totalVms", 87,
                "running", 72, "stopped", 10, "suspended", 3, "error", 2,
                "vmsByMdc", List.of(
                        Map.of("mdcId", "mdc-01", "running", 35, "stopped", 5, "suspended", 1, "error", 1),
                        Map.of("mdcId", "mdc-02", "running", 25, "stopped", 3, "suspended", 1, "error", 1),
                        Map.of("mdcId", "mdc-03", "running", 12, "stopped", 2, "suspended", 1, "error", 0)
                )
        );
    }

    public Map<String, Object> getUserDashboard() {
        return Map.of(
                "totalUsers", 15, "activeUsers", 12, "pendingUsers", 3,
                "recentActivity", List.of(
                        Map.of("user", "관리자", "action", "VM 생성", "target", "web-server-05", "time", "2026-08-11T14:30:00Z"),
                        Map.of("user", "홍길동", "action", "디스크 확장", "target", "data-disk-03", "time", "2026-08-11T13:15:00Z"),
                        Map.of("user", "김영희", "action", "로그인", "target", "-", "time", "2026-08-11T12:00:00Z")
                )
        );
    }

    public Map<String, Object> getNetworkTopology() {
        return Map.of(
                "nodes", List.of(
                        Map.of("id", "mdc-01", "type", "MDC", "label", "서울 MDC", "x", 100, "y", 200),
                        Map.of("id", "mdc-02", "type", "MDC", "label", "대전 MDC", "x", 300, "y", 200),
                        Map.of("id", "mdc-03", "type", "MDC", "label", "부산 MDC", "x", 500, "y", 200),
                        Map.of("id", "aws", "type", "CSP", "label", "AWS", "x", 200, "y", 50),
                        Map.of("id", "gcp", "type", "CSP", "label", "GCP", "x", 400, "y", 50)
                ),
                "edges", List.of(
                        Map.of("from", "mdc-01", "to", "mdc-02", "bandwidth", "10Gbps", "status", "UP"),
                        Map.of("from", "mdc-02", "to", "mdc-03", "bandwidth", "10Gbps", "status", "UP"),
                        Map.of("from", "mdc-01", "to", "aws", "bandwidth", "1Gbps", "status", "UP"),
                        Map.of("from", "mdc-02", "to", "gcp", "bandwidth", "1Gbps", "status", "UP")
                )
        );
    }

    // ── 이벤트/알람 ─────────────────────────────────────────────

    public List<Map<String, Object>> getEvents() {
        return List.of(
                Map.of("eventId", 1, "mdcId", "mdc-01", "severity", "WARNING",
                        "source", "node-03", "message", "CPU 사용률 88% 초과",
                        "time", "2026-08-11T14:30:00Z", "status", "OPEN"),
                Map.of("eventId", 2, "mdcId", "mdc-03", "severity", "CRITICAL",
                        "source", "node-06", "message", "디스크 사용률 95% 초과",
                        "time", "2026-08-11T14:15:00Z", "status", "OPEN"),
                Map.of("eventId", 3, "mdcId", "mdc-01", "severity", "INFO",
                        "source", "gpu-01", "message", "GPU 온도 75°C 도달",
                        "time", "2026-08-11T14:00:00Z", "status", "ACKNOWLEDGED"),
                Map.of("eventId", 4, "mdcId", "mdc-02", "severity", "WARNING",
                        "source", "san-switch-01", "message", "SFP 포트 3 광출력 저하",
                        "time", "2026-08-11T13:30:00Z", "status", "RESOLVED")
        );
    }

    public List<Map<String, Object>> getAlarmRules() {
        return List.of(
                Map.of("ruleId", 1, "name", "CPU 사용률 경고", "metricName", "cpu_usage",
                        "condition", "gt", "threshold", 80.0, "severity", "WARNING",
                        "notifyChannel", "email", "enabled", true),
                Map.of("ruleId", 2, "name", "메모리 사용률 위험", "metricName", "mem_usage",
                        "condition", "gt", "threshold", 90.0, "severity", "CRITICAL",
                        "notifyChannel", "slack", "enabled", true),
                Map.of("ruleId", 3, "name", "디스크 사용률 경고", "metricName", "disk_usage",
                        "condition", "gt", "threshold", 85.0, "severity", "WARNING",
                        "notifyChannel", "email", "enabled", true),
                Map.of("ruleId", 4, "name", "GPU 온도 경고", "metricName", "gpu_temperature",
                        "condition", "gt", "threshold", 80.0, "severity", "WARNING",
                        "notifyChannel", "webhook", "enabled", false)
        );
    }

    public List<Map<String, Object>> getAlarmHistory() {
        return List.of(
                Map.of("alarmId", 1, "ruleId", 1, "mdcId", "mdc-01", "instance", "node-03",
                        "value", 88.2, "severity", "WARNING", "status", "OPEN",
                        "triggeredAt", "2026-08-11T14:30:00Z"),
                Map.of("alarmId", 2, "ruleId", 2, "mdcId", "mdc-03", "instance", "node-06",
                        "value", 95.1, "severity", "CRITICAL", "status", "OPEN",
                        "triggeredAt", "2026-08-11T14:15:00Z"),
                Map.of("alarmId", 3, "ruleId", 3, "mdcId", "mdc-02", "instance", "node-04",
                        "value", 87.3, "severity", "WARNING", "status", "RESOLVED",
                        "triggeredAt", "2026-08-11T10:00:00Z", "resolvedAt", "2026-08-11T11:30:00Z")
        );
    }

    // ── 자산/랙/IP ──────────────────────────────────────────────

    public List<Map<String, Object>> getAssets() {
        return List.of(
                Map.of("assetId", 1, "mdcId", "mdc-01", "type", "SERVER", "name", "sv-seoul-01",
                        "manufacturer", "Dell", "model", "PowerEdge R750",
                        "serialNumber", "SV01-2024-001", "location", "Rack-A-01-U1",
                        "status", "ACTIVE", "purchaseDate", "2024-06-15"),
                Map.of("assetId", 2, "mdcId", "mdc-01", "type", "GPU_SERVER", "name", "sv-seoul-gpu-01",
                        "manufacturer", "Dell", "model", "PowerEdge XE9680",
                        "serialNumber", "SV01-2024-005", "location", "Rack-A-02-U1",
                        "status", "ACTIVE", "purchaseDate", "2024-08-20"),
                Map.of("assetId", 3, "mdcId", "mdc-01", "type", "SWITCH", "name", "sw-seoul-san-01",
                        "manufacturer", "Brocade", "model", "G630",
                        "serialNumber", "SW01-2024-001", "location", "Rack-A-01-U40",
                        "status", "ACTIVE", "purchaseDate", "2024-06-15")
        );
    }

    public List<Map<String, Object>> getRacks() {
        return List.of(
                Map.of("rackId", 1, "mdcId", "mdc-01", "name", "Rack-A-01", "totalUnits", 42,
                        "usedUnits", 28, "powerCapacityKw", 10.0, "currentPowerKw", 6.8,
                        "units", List.of(
                                Map.of("position", "U1-U2", "assetName", "sv-seoul-01", "type", "SERVER"),
                                Map.of("position", "U3-U4", "assetName", "sv-seoul-02", "type", "SERVER"),
                                Map.of("position", "U40", "assetName", "sw-seoul-san-01", "type", "SWITCH"),
                                Map.of("position", "U41-U42", "assetName", "UPS-01", "type", "UPS")
                        )),
                Map.of("rackId", 2, "mdcId", "mdc-01", "name", "Rack-A-02", "totalUnits", 42,
                        "usedUnits", 16, "powerCapacityKw", 15.0, "currentPowerKw", 9.2,
                        "units", List.of(
                                Map.of("position", "U1-U8", "assetName", "sv-seoul-gpu-01", "type", "GPU_SERVER")
                        ))
        );
    }

    public List<Map<String, Object>> getIpAllocations() {
        return List.of(
                Map.of("ipId", 1, "mdcId", "mdc-01", "address", "10.0.1.10", "subnet", "10.0.1.0/24",
                        "assignedTo", "sv-seoul-01", "type", "MANAGEMENT", "status", "ALLOCATED"),
                Map.of("ipId", 2, "mdcId", "mdc-01", "address", "10.0.1.11", "subnet", "10.0.1.0/24",
                        "assignedTo", "sv-seoul-02", "type", "MANAGEMENT", "status", "ALLOCATED"),
                Map.of("ipId", 3, "mdcId", "mdc-01", "address", "10.0.1.20", "subnet", "10.0.1.0/24",
                        "assignedTo", null, "type", "MANAGEMENT", "status", "AVAILABLE"),
                Map.of("ipId", 4, "mdcId", "mdc-01", "address", "192.168.1.10", "subnet", "192.168.1.0/24",
                        "assignedTo", "vm-web-01", "type", "SERVICE", "status", "ALLOCATED")
        );
    }

    // ── 카탈로그 ────────────────────────────────────────────────

    public List<Map<String, Object>> getCatalogItems() {
        return List.of(
                Map.of("catalogId", 1, "name", "웹 서버 (Small)", "description", "2vCPU, 4GB RAM, 50GB SSD",
                        "cpu", 2, "memoryGb", 4, "diskGb", 50, "os", "Ubuntu 22.04",
                        "monthlyPrice", 50000, "category", "COMPUTE"),
                Map.of("catalogId", 2, "name", "웹 서버 (Medium)", "description", "4vCPU, 8GB RAM, 100GB SSD",
                        "cpu", 4, "memoryGb", 8, "diskGb", 100, "os", "Ubuntu 22.04",
                        "monthlyPrice", 100000, "category", "COMPUTE"),
                Map.of("catalogId", 3, "name", "GPU 서버", "description", "8vCPU, 32GB RAM, 500GB SSD, A100 1EA",
                        "cpu", 8, "memoryGb", 32, "diskGb", 500, "os", "Ubuntu 22.04",
                        "gpu", "A100", "monthlyPrice", 500000, "category", "GPU"),
                Map.of("catalogId", 4, "name", "데이터 볼륨 (100GB)", "description", "추가 SSD 볼륨",
                        "diskGb", 100, "diskType", "SSD", "monthlyPrice", 10000, "category", "STORAGE")
        );
    }

    // ── 로그 ────────────────────────────────────────────────────

    public List<Map<String, Object>> getSystemLogs() {
        return List.of(
                Map.of("logId", 1, "mdcId", "mdc-01", "source", "node-01", "level", "INFO",
                        "message", "System boot completed successfully", "time", "2026-08-11T00:00:15Z"),
                Map.of("logId", 2, "mdcId", "mdc-01", "source", "node-03", "level", "WARNING",
                        "message", "High CPU usage detected: 88.2%", "time", "2026-08-11T14:30:00Z"),
                Map.of("logId", 3, "mdcId", "mdc-03", "source", "node-06", "level", "ERROR",
                        "message", "Disk /dev/sda1 usage exceeded 95%", "time", "2026-08-11T14:15:00Z"),
                Map.of("logId", 4, "mdcId", "mdc-02", "source", "san-switch-01", "level", "WARNING",
                        "message", "SFP port 3 Rx power below threshold", "time", "2026-08-11T13:30:00Z")
        );
    }
}
