package com.innogrid.aims.monitoring.service.resourceexplorer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.entity.RoleAsset;
import com.innogrid.aims.infra.entity.RoleNamespace;
import com.innogrid.aims.infra.repository.RoleAssetRepository;
import com.innogrid.aims.infra.repository.RoleNamespaceRepository;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;
import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;
import com.innogrid.aims.monitoring.repository.MonitoringTargetRepository;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MonitoringCapabilityResolver;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MonitoringCapabilityResolver.Snapshot;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.PhysicalTreeBuilder;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.Sebu1VirtualResourceProvider;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.Sebu1VirtualResourceProvider.VirtualResource;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugStatusMapper;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbInfra;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbNode;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbSnapshot;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 패널들이 공유하는 "사용자 역할 스코프의 자원 목록" 조회 서비스.
 *
 * 트리(PhysicalTreeBuilder·TumblebugTreeBuilder)와 같은 원장·같은 스냅샷을 쓰므로
 * 트리에 보이는 자원 수와 패널의 카운트가 항상 일치한다 (docs/06 §9.1).
 * <ul>
 *   <li>물리 호스트: role_assets → SERVER/GPU_SERVER 자산. 실행 상태는 자산 상태 + IPMI 전원 + exporter up 으로 판정.</li>
 *   <li>1세부 가상자원: 물리 호스트에 연결된 VM (Sebu1VirtualResourceProvider).</li>
 *   <li>Tumblebug VM: role_namespaces → Namespace 의 모든 Infra·Node. 상태는 Tumblebug 상태값을 §6.1 버킷으로 집계.</li>
 * </ul>
 * 같은 자원이 여러 역할에 매핑되면 역할별 목록에는 각각 들어가고, 전체 집계는 자원 ID 로 중복을 제거한다.
 */
@Service
public class ResourceScopeService {

    /**
     * 스코프 내 물리 호스트 1건 (역할별).
     * alias 는 node_exporter 타겟의 별칭, instance 는 node_exporter 의 Thanos instance 라벨(address:port),
     * ipAddresses 는 이 자산에 연결된 exporter 타겟 주소 목록 — 각각 없으면 null / 빈 목록.
     */
    public record ScopedHost(String role, Long assetId, String name, String alias, String mdcId, ResourceStatus status,
                             String instance, List<String> ipAddresses) {
        public ScopedHost {
            ipAddresses = ipAddresses == null ? List.of() : List.copyOf(ipAddresses);
        }
    }

    /**
     * 스코프 내 가상머신 1건 (역할별). resourceId 는 SEBU1:{externalId} 또는 TB:{ns}/{infra}/{node}.
     * 소속 영역은 1세부 VM 이면 물리 호스트의 mdcId, Tumblebug VM 이면 nsId 만 채워진다.
     */
    public record ScopedVm(String role, String resourceId, String name, ResourceStatus status, String mdcId, String nsId) {}

    /** 스코프 내 Tumblebug Namespace 1건 (역할별) — VM 이 없어도 역할 패널 영역 테이블에 행으로 나온다 */
    public record ScopedNamespace(String role, String nsId, String name) {}

    /** 사용자 역할 스코프의 자원 전체 */
    public record Scope(Set<String> roles, List<ScopedHost> hosts, List<ScopedVm> vms, List<ScopedNamespace> namespaces) {

        static Scope empty() {
            return new Scope(Set.of(), List.of(), List.of(), List.of());
        }

        public List<ScopedHost> hostsOf(String role) {
            return hosts.stream().filter(h -> h.role().equals(role)).toList();
        }

        public List<ScopedVm> vmsOf(String role) {
            return vms.stream().filter(v -> v.role().equals(role)).toList();
        }

        public List<ScopedNamespace> namespacesOf(String role) {
            return namespaces.stream().filter(n -> n.role().equals(role)).toList();
        }

        /** 역할을 제거한 전체 집계용 — assets.id 기준 중복 제거 */
        public Collection<ScopedHost> distinctHosts() {
            Map<Long, ScopedHost> byId = new LinkedHashMap<>();
            hosts.forEach(h -> byId.putIfAbsent(h.assetId(), h));
            return byId.values();
        }

        /** 역할을 제거한 전체 집계용 — resourceId 기준 중복 제거 */
        public Collection<ScopedVm> distinctVms() {
            Map<String, ScopedVm> byId = new LinkedHashMap<>();
            vms.forEach(v -> byId.putIfAbsent(v.resourceId(), v));
            return byId.values();
        }

        public Set<String> mdcIds() {
            return distinctHosts().stream().map(ScopedHost::mdcId).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private final AuthService authService;
    private final RoleAssetRepository roleAssetRepository;
    private final RoleNamespaceRepository roleNamespaceRepository;
    private final MonitoringTargetRepository monitoringTargetRepository;
    private final MonitoringCapabilityResolver capabilityResolver;
    private final TumblebugTreeBuilder tumblebugTreeBuilder;
    private final Sebu1VirtualResourceProvider virtualResourceProvider;

    public ResourceScopeService(AuthService authService,
                                RoleAssetRepository roleAssetRepository,
                                RoleNamespaceRepository roleNamespaceRepository,
                                MonitoringTargetRepository monitoringTargetRepository,
                                MonitoringCapabilityResolver capabilityResolver,
                                TumblebugTreeBuilder tumblebugTreeBuilder,
                                Sebu1VirtualResourceProvider virtualResourceProvider) {
        this.authService = authService;
        this.roleAssetRepository = roleAssetRepository;
        this.roleNamespaceRepository = roleNamespaceRepository;
        this.monitoringTargetRepository = monitoringTargetRepository;
        this.capabilityResolver = capabilityResolver;
        this.tumblebugTreeBuilder = tumblebugTreeBuilder;
        this.virtualResourceProvider = virtualResourceProvider;
    }

    /** 현재 사용자 토큰의 역할 스코프 */
    public Scope currentUserScope() {
        return scopeOf(authService.currentUserRoleIds());
    }

    public Scope scopeOf(Collection<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            return Scope.empty();
        }
        Set<String> roles = new LinkedHashSet<>(roleNames);
        Snapshot metrics = capabilityResolver.snapshot();

        List<ScopedHost> hosts = new ArrayList<>();
        List<ScopedVm> vms = new ArrayList<>();
        List<ScopedNamespace> namespaces = new ArrayList<>();
        loadPhysical(roles, metrics, hosts, vms);
        loadTumblebug(roles, vms, namespaces);
        return new Scope(roles, List.copyOf(hosts), List.copyOf(vms), List.copyOf(namespaces));
    }

    // ── 물리 호스트 + 1세부 가상자원 ────────────────────────────

    private void loadPhysical(Set<String> roles, Snapshot metrics, List<ScopedHost> hosts, List<ScopedVm> vms) {
        Map<String, Map<Long, Asset>> hostsByRole = new LinkedHashMap<>();
        for (RoleAsset mapping : roleAssetRepository.findByRole_NameIn(roles)) {
            Asset asset = mapping.getAsset();
            if (asset == null || !PhysicalTreeBuilder.HOST_TYPES.contains(asset.getType())) {
                continue;
            }
            hostsByRole.computeIfAbsent(mapping.getRole().getName(), k -> new LinkedHashMap<>()).put(asset.getId(), asset);
        }
        if (hostsByRole.isEmpty()) {
            return;
        }
        Set<Long> assetIds = hostsByRole.values().stream().flatMap(m -> m.keySet().stream()).collect(Collectors.toSet());
        Map<Long, List<MonitoringTarget>> targetsByAsset = monitoringTargetRepository.findByAsset_IdIn(assetIds).stream()
                .collect(Collectors.groupingBy(t -> t.getAsset().getId()));

        // 1세부 VM 목록은 자산당 한 번만 조회해 재사용한다 (결과는 해당 자산을 가진 역할마다 각각 추가된다)
        Map<Long, List<VirtualResource>> vmsByAsset = new LinkedHashMap<>();
        for (Map.Entry<String, Map<Long, Asset>> entry : hostsByRole.entrySet()) {
            String role = entry.getKey();
            for (Asset asset : entry.getValue().values()) {
                List<MonitoringTarget> targets = targetsByAsset.getOrDefault(asset.getId(), List.of());
                MonitoringTarget nodeExporter = targets.stream()
                        .filter(t -> t.getExporterType() == ExporterType.NODE_EXPORTER)
                        .findFirst().orElse(null);
                List<String> addresses = targets.stream().map(MonitoringTarget::getAddress).distinct().toList();
                hosts.add(new ScopedHost(role, asset.getId(), asset.getName(),
                        nodeExporter == null ? null : nodeExporter.getAlias(),
                        asset.getMdcId(), hostStatus(asset, targets, metrics),
                        nodeExporter == null ? null : nodeExporter.getScrapeEndpoint(),
                        addresses));
                for (VirtualResource vm : vmsByAsset.computeIfAbsent(asset.getId(), id -> virtualResourceProvider.findByHost(asset))) {
                    vms.add(new ScopedVm(role, "SEBU1:" + vm.externalId(), vm.name(), sebu1Status(vm.status()),
                            asset.getMdcId(), null));
                }
            }
        }
    }

    /**
     * 물리 호스트 실행 상태 판정.
     * <ol>
     *   <li>자산 상태 INACTIVE/DECOMMISSIONED → STOP, MAINTENANCE → PENDING</li>
     *   <li>Thanos 정상 스냅샷이 없음(기동 직후 장애) → PENDING (관측 계통 장애를 호스트 오류로 집계하지 않는다)</li>
     *   <li>ipmi_exporter 가 있고 섀시 전원이 꺼져 있으면 → STOP</li>
     *   <li>exporter 미등록 → PENDING (상태 미확정)</li>
     *   <li>exporter 중 하나라도 수집 중 → RUNNING, 전부 미수집 → ERROR</li>
     * </ol>
     */
    // TODO:: 1세부가 물리 호스트 실행 상태를 별도 API 로 제공하면 그 값을 우선 사용하도록 교체
    static ResourceStatus hostStatus(Asset asset, List<MonitoringTarget> targets, Snapshot metrics) {
        switch (asset.getStatus()) {
            case INACTIVE, DECOMMISSIONED -> { return ResourceStatus.STOP; }
            case MAINTENANCE -> { return ResourceStatus.PENDING; }
            case ACTIVE -> { }
        }
        if (metrics.degraded()) {
            return ResourceStatus.PENDING;
        }
        for (MonitoringTarget target : targets) {
            if (target.getExporterType() == ExporterType.IPMI_EXPORTER
                    && Boolean.FALSE.equals(metrics.chassisPowerOn(target.getScrapeEndpoint()))) {
                return ResourceStatus.STOP;
            }
        }
        if (targets.isEmpty()) {
            return ResourceStatus.PENDING;
        }
        boolean anyCollecting = targets.stream()
                .anyMatch(t -> metrics.statusOf(t.getScrapeEndpoint()) == MonitoringStatus.COLLECTING);
        return anyCollecting ? ResourceStatus.RUNNING : ResourceStatus.ERROR;
    }

    /** 1세부 VM 상태 — 우리 4종 이름과 같으면 그대로, 아니면 Tumblebug 어휘 매핑을 재사용 */
    public static ResourceStatus sebu1Status(String status) {
        if (status != null) {
            for (ResourceStatus candidate : ResourceStatus.values()) {
                if (candidate.name().equalsIgnoreCase(status.trim())) {
                    return candidate;
                }
            }
        }
        return TumblebugStatusMapper.bucket(status);
    }

    // ── Tumblebug VM ────────────────────────────────────────────

    private void loadTumblebug(Set<String> roles, List<ScopedVm> vms, List<ScopedNamespace> namespaces) {
        Map<String, Set<String>> namespacesByRole = new LinkedHashMap<>();
        for (RoleNamespace mapping : roleNamespaceRepository.findByRole_NameIn(roles)) {
            namespacesByRole.computeIfAbsent(mapping.getRole().getName(), k -> new TreeSet<>()).add(mapping.getNsId());
        }
        if (namespacesByRole.isEmpty()) {
            return;
        }
        TbSnapshot tb = tumblebugTreeBuilder.snapshot();
        for (Map.Entry<String, Set<String>> entry : namespacesByRole.entrySet()) {
            String role = entry.getKey();
            for (String nsId : entry.getValue()) {
                String nsName = tb.namespaceNames().get(nsId);
                if (nsName == null) {
                    continue;                                       // Tumblebug 에 없는 Namespace 는 트리와 같이 건너뜀
                }
                namespaces.add(new ScopedNamespace(role, nsId, nsName));
                for (TbInfra infra : tb.infrasByNamespace().getOrDefault(nsId, List.of())) {
                    for (TbNode node : infra.nodes()) {
                        vms.add(new ScopedVm(role, "TB:" + nsId + "/" + infra.id() + "/" + node.id(), node.name(),
                                TumblebugStatusMapper.bucket(node.status()), null, nsId));
                    }
                }
            }
        }
    }
}
