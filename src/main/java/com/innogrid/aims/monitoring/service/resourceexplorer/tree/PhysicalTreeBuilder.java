package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.entity.RoleAsset;
import com.innogrid.aims.infra.repository.RoleAssetRepository;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeNodeResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeNodeType;
import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;
import com.innogrid.aims.monitoring.repository.MonitoringTargetRepository;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MonitoringCapabilityResolver.GpuDevice;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MonitoringCapabilityResolver.Snapshot;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.Sebu1VirtualResourceProvider.VirtualResource;
import org.springframework.stereotype.Component;

/**
 * 자원 탐색기 트리의 물리 가지(1세부)를 조립한다 (docs/06 §2, §3, §5, §9).
 *
 * <pre>
 * 역할 → 물리 노드 → MDC → 물리 호스트 → { 모니터링 항목(+GPU, Hardware), 가상자원 → 가상자원(VM) → 모니터링 항목 }
 * </pre>
 *
 * <ul>
 *   <li>역할 스코프: {@code role_assets} (Role → Asset.id). 물리 호스트는 SERVER / GPU_SERVER 자산이다.</li>
 *   <li>MDC: 자산의 {@code mdcId} 로 그룹화한다 (MDC 엔티티가 없어 표시명도 mdcId).</li>
 *   <li>모니터링 항목: 자산에 연결된 monitoring_target 의 exporter 종류로 항목을 만들고,
 *       Thanos 스냅샷으로 수집 상태·GPU 장치·하드웨어 센서 존재를 판정한다.</li>
 *   <li>가상자원: {@link Sebu1VirtualResourceProvider} 가 돌려준 VM 만 연결한다 (이름/IP 추정 없음).</li>
 * </ul>
 *
 * 트리 항목 ID 체계 — 같은 자산이 여러 역할에 있으면 역할 경로가 달라 id 도 다르다. resourceId 는 자산 id 로 고정.
 * <pre>
 * physical:{role} | mdc:{role}:{mdcId} | host:{role}:{assetId}
 * cat:{ownerNodeId}:{TYPE} | gpus:{ownerNodeId} | gpu:{ownerNodeId}:{uuid} | hw:{ownerNodeId} | hwcat:{ownerNodeId}:{TYPE}
 * vres:{hostNodeId} | vm:{hostNodeId}:{externalId}
 * </pre>
 */
@Component
public class PhysicalTreeBuilder {

    /** 트리에 물리 호스트로 노출하는 자산 유형 — 스위치·스토리지 등은 제외한다 */
    public static final Set<Asset.AssetType> HOST_TYPES = EnumSet.of(Asset.AssetType.SERVER, Asset.AssetType.GPU_SERVER);

    /** node_exporter 항목 (docs/06 §5.2 순서). Disk 는 화면용 그룹으로 하위에 Disk I/O·File System 을 둔다 */
    static final List<ResourceTreeNodeType> NODE_EXPORTER_ITEMS = List.of(
            ResourceTreeNodeType.CPU, ResourceTreeNodeType.MEMORY, ResourceTreeNodeType.DISK,
            ResourceTreeNodeType.NETWORK, ResourceTreeNodeType.PROCESS, ResourceTreeNodeType.SERVICE_STATUS,
            ResourceTreeNodeType.SWAP, ResourceTreeNodeType.SYSTEM);
    static final List<ResourceTreeNodeType> DISK_ITEMS = List.of(
            ResourceTreeNodeType.DISK_IO, ResourceTreeNodeType.FILE_SYSTEM);

    /** Hardware 세부 항목 → 존재를 확인할 ipmi_exporter 메트릭 (docs/06 §5.5, §9.4 NO_DATA 규칙) */
    static final Map<ResourceTreeNodeType, List<String>> HARDWARE_ITEMS = new LinkedHashMap<>();
    static {
        HARDWARE_ITEMS.put(ResourceTreeNodeType.HW_TEMPERATURE, List.of("ipmi_temperature_celsius"));
        HARDWARE_ITEMS.put(ResourceTreeNodeType.HW_FAN, List.of("ipmi_fan_speed_rpm"));
        HARDWARE_ITEMS.put(ResourceTreeNodeType.HW_POWER, List.of("ipmi_power_watts", "ipmi_dcmi_power_consumption_watts"));
        HARDWARE_ITEMS.put(ResourceTreeNodeType.HW_VOLTAGE, List.of("ipmi_voltage_volts", "ipmi_current_amperes"));
        HARDWARE_ITEMS.put(ResourceTreeNodeType.HW_HEALTH, List.of("ipmi_sensor_state", "ipmi_chassis_power_state"));
        HARDWARE_ITEMS.put(ResourceTreeNodeType.HW_EVENTS, List.of("ipmi_sel_logs_count"));
    }

    private final RoleAssetRepository roleAssetRepository;
    private final MonitoringTargetRepository monitoringTargetRepository;
    private final MonitoringCapabilityResolver capabilityResolver;
    private final Sebu1VirtualResourceProvider virtualResourceProvider;

    public PhysicalTreeBuilder(RoleAssetRepository roleAssetRepository,
                               MonitoringTargetRepository monitoringTargetRepository,
                               MonitoringCapabilityResolver capabilityResolver,
                               Sebu1VirtualResourceProvider virtualResourceProvider) {
        this.roleAssetRepository = roleAssetRepository;
        this.monitoringTargetRepository = monitoringTargetRepository;
        this.capabilityResolver = capabilityResolver;
        this.virtualResourceProvider = virtualResourceProvider;
    }

    /**
     * 역할 이름별 '물리 노드' 가지를 만든다. 물리 호스트가 하나도 없는 역할은 결과에 포함하지 않는다.
     *
     * @param roleNames 사용자 토큰의 역할 이름
     * @param mdcFilter 비어 있지 않으면 해당 MDC 만 포함
     * @return 역할 이름 → PHYSICAL_ROOT 노드 (parentId 는 role:{role})
     */
    public Map<String, ResourceTreeNodeResponse> build(Collection<String> roleNames, Set<String> mdcFilter) {
        if (roleNames.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<Long, Asset>> hostsByRole = new LinkedHashMap<>();
        for (RoleAsset mapping : roleAssetRepository.findByRole_NameIn(roleNames)) {
            Asset asset = mapping.getAsset();
            if (asset == null || !HOST_TYPES.contains(asset.getType())) {
                continue;
            }
            hostsByRole.computeIfAbsent(mapping.getRole().getName(), k -> new LinkedHashMap<>())
                    .put(asset.getId(), asset);
        }
        if (hostsByRole.isEmpty()) {
            return Map.of();
        }

        Set<Long> assetIds = hostsByRole.values().stream()
                .flatMap(m -> m.keySet().stream())
                .collect(Collectors.toSet());
        Map<Long, List<MonitoringTarget>> targetsByAsset = monitoringTargetRepository.findByAsset_IdIn(assetIds).stream()
                .collect(Collectors.groupingBy(t -> t.getAsset().getId()));
        Snapshot snapshot = capabilityResolver.snapshot();

        Map<String, ResourceTreeNodeResponse> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<Long, Asset>> entry : hostsByRole.entrySet()) {
            String role = entry.getKey();
            ResourceTreeNodeResponse physicalRoot = physicalRoot(role, entry.getValue().values(), mdcFilter, targetsByAsset, snapshot);
            if (physicalRoot != null) {
                result.put(role, physicalRoot);
            }
        }
        return result;
    }

    // ── 계층 조립 ───────────────────────────────────────────────

    private ResourceTreeNodeResponse physicalRoot(String role,
                                                  Collection<Asset> hosts,
                                                  Set<String> mdcFilter,
                                                  Map<Long, List<MonitoringTarget>> targetsByAsset,
                                                  Snapshot snapshot) {
        String physicalId = "physical:" + role;
        Map<String, List<Asset>> byMdc = hosts.stream()
                .filter(a -> mdcFilter.isEmpty() || mdcFilter.contains(a.getMdcId()))
                .collect(Collectors.groupingBy(Asset::getMdcId, TreeMap::new, Collectors.toList()));

        List<ResourceTreeNodeResponse> mdcs = new ArrayList<>();
        for (Map.Entry<String, List<Asset>> mdc : byMdc.entrySet()) {
            String mdcNodeId = "mdc:" + role + ":" + mdc.getKey();
            List<ResourceTreeNodeResponse> hostNodes = mdc.getValue().stream()
                    .sorted(Comparator.comparing(Asset::getName, String.CASE_INSENSITIVE_ORDER))
                    .map(a -> hostNode(a, role, mdcNodeId, targetsByAsset.getOrDefault(a.getId(), List.of()), snapshot))
                    .toList();
            mdcs.add(ResourceTreeNodeResponse.of(mdcNodeId, physicalId, mdc.getKey(), ResourceTreeNodeType.MDC,
                    mdc.getKey(), role, mdc.getKey(), null, null, hostNodes));
        }
        if (mdcs.isEmpty()) {
            return null;
        }
        return ResourceTreeNodeResponse.of(physicalId, "role:" + role, ResourceTreeNodeType.PHYSICAL_ROOT.displayName(),
                ResourceTreeNodeType.PHYSICAL_ROOT, null, role, null, null, null, mdcs);
    }

    private ResourceTreeNodeResponse hostNode(Asset asset,
                                              String role,
                                              String mdcNodeId,
                                              List<MonitoringTarget> targets,
                                              Snapshot snapshot) {
        String hostNodeId = "host:" + role + ":" + asset.getId();
        String mdcId = asset.getMdcId();
        Map<ExporterType, MonitoringTarget> byExporter = targets.stream()
                .collect(Collectors.toMap(MonitoringTarget::getExporterType, Function.identity(), (a, b) -> a));

        List<ResourceTreeNodeResponse> children = new ArrayList<>();
        MonitoringTarget nodeExporter = byExporter.get(ExporterType.NODE_EXPORTER);
        if (nodeExporter != null) {
            children.addAll(nodeExporterItems(hostNodeId, role, mdcId, snapshot.statusOf(nodeExporter.getScrapeEndpoint())));
        }
        MonitoringTarget dcgm = byExporter.get(ExporterType.DCGM_EXPORTER);
        if (dcgm != null) {
            String instance = dcgm.getScrapeEndpoint();
            ResourceTreeNodeResponse gpuGroup = gpuGroup(hostNodeId, role, mdcId,
                    snapshot.gpuDevices(instance), snapshot.statusOf(instance));
            if (gpuGroup != null) {
                children.add(gpuGroup);
            }
        }
        MonitoringTarget ipmi = byExporter.get(ExporterType.IPMI_EXPORTER);
        if (ipmi != null) {
            ResourceTreeNodeResponse hardware = hardwareGroup(hostNodeId, role, mdcId, ipmi.getScrapeEndpoint(), snapshot);
            if (hardware != null) {
                children.add(hardware);
            }
        }
        // TODO:: SNMP_EXPORTER 는 현재 SAN 스위치 대상(swFCPort*)이라 물리 호스트 항목 판정에서 제외 — 호스트용 MIB 확정 시 Hardware 에 편입

        List<VirtualResource> vms = virtualResourceProvider.findByHost(asset);
        if (!vms.isEmpty()) {
            children.add(virtualResourceGroup(hostNodeId, role, mdcId, vms, snapshot));
        }

        return ResourceTreeNodeResponse.of(hostNodeId, mdcNodeId, asset.getName(), ResourceTreeNodeType.PHYSICAL_HOST,
                String.valueOf(asset.getId()), role, mdcId, asset.getStatus().name(),
                hostMonitoringStatus(targets, snapshot), children);
    }

    /** 호스트 요약 수집 상태 — exporter 없음: NOT_CONFIGURED, 하나라도 up: COLLECTING, 아니면 UNAVAILABLE */
    private static MonitoringStatus hostMonitoringStatus(List<MonitoringTarget> targets, Snapshot snapshot) {
        if (targets.isEmpty()) {
            return MonitoringStatus.NOT_CONFIGURED;
        }
        boolean anyUp = targets.stream()
                .anyMatch(t -> snapshot.statusOf(t.getScrapeEndpoint()) == MonitoringStatus.COLLECTING);
        return anyUp ? MonitoringStatus.COLLECTING : MonitoringStatus.UNAVAILABLE;
    }

    // ── 모니터링 항목 ───────────────────────────────────────────

    /** node_exporter 항목 8종 (Disk 하위 Disk I/O·File System 포함). Tumblebug VM 가지에서도 재사용한다 */
    static List<ResourceTreeNodeResponse> nodeExporterItems(String ownerId,
                                                            String role,
                                                            String mdcId,
                                                            MonitoringStatus status) {
        List<ResourceTreeNodeResponse> items = new ArrayList<>();
        for (ResourceTreeNodeType type : NODE_EXPORTER_ITEMS) {
            List<ResourceTreeNodeResponse> sub = type == ResourceTreeNodeType.DISK
                    ? DISK_ITEMS.stream().map(d -> category(ownerId, d, role, mdcId, status, List.of())).toList()
                    : List.of();
            items.add(category(ownerId, type, role, mdcId, status, sub));
        }
        return items;
    }

    private static ResourceTreeNodeResponse category(String ownerId,
                                                     ResourceTreeNodeType type,
                                                     String role,
                                                     String mdcId,
                                                     MonitoringStatus status,
                                                     List<ResourceTreeNodeResponse> children) {
        return ResourceTreeNodeResponse.of("cat:" + ownerId + ":" + type, ownerId, type.displayName(), type,
                null, role, mdcId, null, status, children);
    }

    /** GPU 장치 메트릭이 확인된 경우에만 생성한다 (장치 목록이 비면 null). Tumblebug VM 가지에서도 재사용한다 */
    static ResourceTreeNodeResponse gpuGroup(String ownerId,
                                             String role,
                                             String mdcId,
                                             List<GpuDevice> devices,
                                             MonitoringStatus status) {
        if (devices.isEmpty()) {
            return null;
        }
        String groupId = "gpus:" + ownerId;
        List<ResourceTreeNodeResponse> gpus = devices.stream()
                .map(d -> ResourceTreeNodeResponse.of("gpu:" + ownerId + ":" + d.uuid(), groupId,
                        "GPU " + (d.index() == null ? d.uuid() : d.index()), ResourceTreeNodeType.GPU_DEVICE,
                        d.uuid(), role, mdcId, null, status, List.of()))
                .toList();
        return ResourceTreeNodeResponse.of(groupId, ownerId, ResourceTreeNodeType.GPU_GROUP.displayName(),
                ResourceTreeNodeType.GPU_GROUP, null, role, mdcId, null, status, gpus);
    }

    /** ipmi_exporter 가 등록되고 센서 메트릭이 확인된 세부 항목만 생성한다. 물리 호스트에만 귀속 */
    private static ResourceTreeNodeResponse hardwareGroup(String ownerId,
                                                          String role,
                                                          String mdcId,
                                                          String instance,
                                                          Snapshot snapshot) {
        MonitoringStatus status = snapshot.statusOf(instance);
        String groupId = "hw:" + ownerId;
        List<ResourceTreeNodeResponse> items = HARDWARE_ITEMS.entrySet().stream()
                .filter(e -> snapshot.hasAnyMetric(instance, e.getValue()))
                .map(e -> ResourceTreeNodeResponse.of("hwcat:" + ownerId + ":" + e.getKey(), groupId,
                        e.getKey().displayName(), e.getKey(), null, role, mdcId, null, status, List.of()))
                .toList();
        if (items.isEmpty()) {
            return null;
        }
        return ResourceTreeNodeResponse.of(groupId, ownerId, ResourceTreeNodeType.HARDWARE.displayName(),
                ResourceTreeNodeType.HARDWARE, null, role, mdcId, null, status, items);
    }

    // ── 1세부 가상자원 ──────────────────────────────────────────

    private static ResourceTreeNodeResponse virtualResourceGroup(String hostNodeId,
                                                                 String role,
                                                                 String mdcId,
                                                                 List<VirtualResource> vms,
                                                                 Snapshot snapshot) {
        String groupId = "vres:" + hostNodeId;
        List<ResourceTreeNodeResponse> vmNodes = vms.stream()
                .map(vm -> {
                    String vmNodeId = "vm:" + hostNodeId + ":" + vm.externalId();
                    MonitoringStatus status = vm.instance() == null
                            ? MonitoringStatus.NOT_CONFIGURED : snapshot.statusOf(vm.instance());
                    List<ResourceTreeNodeResponse> items = vm.instance() == null
                            ? List.of() : nodeExporterItems(vmNodeId, role, mdcId, status);
                    // GPU 패스스루 VM 의 dcgm 항목은 1세부 VM exporter 라벨 규약 확정 후 추가한다
                    return ResourceTreeNodeResponse.of(vmNodeId, groupId, vm.name(), ResourceTreeNodeType.VIRTUAL_RESOURCE,
                            "SEBU1:" + vm.externalId(), role, mdcId, vm.status(), status, items);
                })
                .toList();
        return ResourceTreeNodeResponse.of(groupId, hostNodeId, ResourceTreeNodeType.VIRTUAL_RESOURCE_GROUP.displayName(),
                ResourceTreeNodeType.VIRTUAL_RESOURCE_GROUP, null, role, mdcId, null, null, vmNodes);
    }
}
