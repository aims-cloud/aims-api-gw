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
        return getTopology(null, "network");
    }

    /**
     * 통합 토폴로지.
     *
     * @param mdcId 배포영역 필터 (null이면 전체)
     * @param view  "compute" — MDC 하위에 컴퓨트 노드(node/dcgm/ipmi exporter) 표시,
     *              "network" — MDC 간 연결 + 네트워크 장비(snmp exporter) 표시 (기본)
     * 노드에는 label(호스트명)과 alias, displayName(별칭표기 토글 표시값)이 함께 담긴다.
     */
    public Map<String, Object> getTopology(String mdcId, String view) {
        boolean computeView = "compute".equalsIgnoreCase(view);

        List<MonitoringTarget> targets = targetRepository.findAll().stream()
                .filter(t -> mdcId == null || mdcId.isBlank() || t.getMdcId().equals(mdcId))
                .toList();

        Set<String> mdcIds = targets.stream()
                .map(MonitoringTarget::getMdcId)
                .collect(Collectors.toCollection(TreeSet::new));

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        int x = 100;
        for (String id : mdcIds) {
            nodes.add(Map.of("id", id, "type", "MDC", "label", id, "x", x, "y", 100));
            x += 250;
        }

        // 뷰별 하위 노드: compute = 컴퓨트 노드, network = 네트워크 장비
        Set<MonitoringTarget.ExporterType> visibleTypes = computeView
                ? Set.of(MonitoringTarget.ExporterType.NODE_EXPORTER,
                        MonitoringTarget.ExporterType.DCGM_EXPORTER,
                        MonitoringTarget.ExporterType.IPMI_EXPORTER)
                : Set.of(MonitoringTarget.ExporterType.SNMP_EXPORTER);

        Map<String, Integer> childOffset = new HashMap<>();
        for (MonitoringTarget target : targets) {
            if (!visibleTypes.contains(target.getExporterType())) continue;

            int offset = childOffset.merge(target.getMdcId(), 1, Integer::sum);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "target-" + target.getId());
            node.put("type", computeView ? "COMPUTE" : "NETWORK");
            node.put("label", target.getHostname());
            node.put("alias", target.getAlias());
            node.put("displayName", target.getDisplayName());
            node.put("mdcId", target.getMdcId());
            node.put("exporterType", target.getExporterType().name());
            node.put("status", target.getStatus().name());
            node.put("x", 100 + (offset - 1) * 150);
            node.put("y", 300);
            nodes.add(node);

            edges.add(Map.of("from", target.getMdcId(), "to", "target-" + target.getId(),
                    "status", target.getStatus().name()));
        }

        // 네트워크 뷰: MDC 간 연결 (인접 MDC끼리)
        if (!computeView) {
            List<String> mdcList = new ArrayList<>(mdcIds);
            for (int i = 0; i < mdcList.size() - 1; i++) {
                edges.add(Map.of("from", mdcList.get(i), "to", mdcList.get(i + 1), "status", "UP"));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("view", computeView ? "compute" : "network");
        result.put("mdcFilter", mdcId);
        result.put("mdcIds", mdcIds);
        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }
}
