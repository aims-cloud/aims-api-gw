package com.innogrid.aims.dashboard.service;

import java.util.*;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.entity.Rack;
import com.innogrid.aims.infra.repository.AssetRepository;
import com.innogrid.aims.infra.repository.RackRepository;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.repository.EventRepository;
import com.innogrid.aims.monitoring.repository.MonitoringTargetRepository;

@Service
public class DashboardService {

    private final AssetRepository assetRepository;
    private final RackRepository rackRepository;
    private final MonitoringTargetRepository targetRepository;
    private final EventRepository eventRepository;
    private final VictoriaMetricsClient vmClient;

    public DashboardService(AssetRepository assetRepository,
                            RackRepository rackRepository,
                            MonitoringTargetRepository targetRepository,
                            EventRepository eventRepository,
                            VictoriaMetricsClient vmClient) {
        this.assetRepository = assetRepository;
        this.rackRepository = rackRepository;
        this.targetRepository = targetRepository;
        this.eventRepository = eventRepository;
        this.vmClient = vmClient;
    }

    public Map<String, Object> getInfraDashboard() {
        List<Asset> assets = assetRepository.findAll();
        List<MonitoringTarget> targets = targetRepository.findAll();

        // MDC별 그룹핑
        Map<String, List<Asset>> byMdc = assets.stream()
                .collect(Collectors.groupingBy(Asset::getMdcId));

        Set<String> mdcIds = new HashSet<>(byMdc.keySet());
        targets.forEach(t -> mdcIds.add(t.getMdcId()));

        List<Map<String, Object>> mdcSummary = mdcIds.stream().map(mdcId -> {
            List<Asset> mdcAssets = byMdc.getOrDefault(mdcId, List.of());
            long serverCount = mdcAssets.stream().filter(a -> a.getType() == Asset.AssetType.SERVER || a.getType() == Asset.AssetType.GPU_SERVER).count();
            long gpuCount = mdcAssets.stream().filter(a -> a.getType() == Asset.AssetType.GPU_SERVER).count();

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("mdcId", mdcId);
            summary.put("nodeCount", serverCount);
            summary.put("gpuCount", gpuCount);
            summary.put("totalAssets", mdcAssets.size());
            return summary;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalMdcCount", mdcIds.size());
        result.put("totalAssets", assets.size());
        result.put("totalTargets", targets.size());
        result.put("mdcSummary", mdcSummary);
        result.put("recentEvents", eventRepository.findAllByOrderByTimeDesc().stream().limit(5).toList());
        return result;
    }

    public Map<String, Object> getNodeStatusDashboard() {
        List<MonitoringTarget> targets = targetRepository.findAll();
        long upCount = targets.stream().filter(t -> t.getStatus() == MonitoringTarget.TargetStatus.UP).count();
        long downCount = targets.stream().filter(t -> t.getStatus() == MonitoringTarget.TargetStatus.DOWN).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalNodes", targets.size());
        result.put("up", upCount);
        result.put("down", downCount);
        result.put("nodes", targets);
        return result;
    }

    public Map<String, Object> getGpuDashboard() {
        List<Asset> gpuAssets = assetRepository.findByTypeOrderByCreatedAtDesc(Asset.AssetType.GPU_SERVER);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalGpuServers", gpuAssets.size());
        result.put("gpuServers", gpuAssets);
        return result;
    }

    public Map<String, Object> getStorageDashboard() {
        List<Asset> storageAssets = assetRepository.findByTypeOrderByCreatedAtDesc(Asset.AssetType.STORAGE);
        List<Rack> racks = rackRepository.findAll();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalStorageAssets", storageAssets.size());
        result.put("storageAssets", storageAssets);
        result.put("totalRacks", racks.size());
        return result;
    }

    public Map<String, Object> getVmStatusDashboard() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "VM 현황은 Cloud-Barista 연동 시 실시간 조회됩니다");

        try {
            double upTargets = vmClient.queryScalar("count(up == 1)");
            result.put("activeTargets", (int) upTargets);
        } catch (Exception e) {
            result.put("activeTargets", 0);
        }
        return result;
    }

    public Map<String, Object> getUserDashboard() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "사용자 현황은 /api/v1/users API를 통해 조회합니다");
        return result;
    }

    public Map<String, Object> getNetworkTopology() {
        List<MonitoringTarget> targets = targetRepository.findAll();

        // MDC별 노드 생성
        Set<String> mdcIds = targets.stream().map(MonitoringTarget::getMdcId).collect(Collectors.toSet());
        List<Map<String, Object>> nodes = new ArrayList<>();
        int x = 100;
        for (String mdcId : mdcIds) {
            nodes.add(Map.of("id", mdcId, "type", "MDC", "label", mdcId, "x", x, "y", 200));
            x += 200;
        }

        // MDC 간 연결 (인접 MDC끼리)
        List<Map<String, Object>> edges = new ArrayList<>();
        List<String> mdcList = new ArrayList<>(mdcIds);
        for (int i = 0; i < mdcList.size() - 1; i++) {
            edges.add(Map.of("from", mdcList.get(i), "to", mdcList.get(i + 1), "status", "UP"));
        }

        return Map.of("nodes", nodes, "edges", edges);
    }
}
