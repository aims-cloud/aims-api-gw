package com.innogrid.aims.dashboard.service;

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
import com.innogrid.aims.infra.repository.RoleAssetRepository;
import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;
import com.innogrid.aims.monitoring.repository.MonitoringTargetRepository;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.PhysicalTreeBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GPU 대시보드의 "볼 수 있는 GPU 노드" — 기준 목록은 DB 다 (Confluence 2320072739 §2).
 *
 * 토큰 역할 → role_assets → 호스트 자산(SERVER·GPU_SERVER) → 그 자산의 DCGM 수집 대상(monitoring_target).
 * Thanos 의 DCGM 시계열은 이 목록의 {@code instance}(address:port)와 같은 것만 값으로 쓴다. 역할 필터는 토큰 역할 안에서만
 * 좁힐 수 있고(넓힐 수 없음), 배포영역 필터는 자산의 MDC 다. 자원 탐색기 스코프(ResourceScopeService)와 같은 원장을 쓰되,
 * 대시보드는 Tumblebug·1세부 VM 조회가 필요 없어 가벼운 별도 경로로 둔다.
 */
@Service
public class GpuNodeScopeService {

    static final String ADMIN_ROLE = "ADMIN";

    /** 스코프 안 GPU 노드 1대(DCGM 수집 대상 1건). roles 는 이 노드를 볼 수 있는 선택 역할들 */
    public record GpuNode(Long assetId, String assetName, String mdcId, String instance, Set<String> roles) {
        public GpuNode {
            roles = Set.copyOf(roles);
        }

        /** 표시·정렬용 대표 역할 — 이름순 첫 역할 */
        public String primaryRole() {
            return roles.stream().sorted().findFirst().orElse(null);
        }
    }

    /**
     * @param admin     토큰에 ADMIN 역할이 있는가(필터와 무관) — 미등록 노드 수는 ADMIN 에게만 준다
     * @param roles     실제로 적용된 역할(토큰 역할 ∩ 역할 필터)
     * @param nodes     볼 수 있는 GPU 노드(instance 기준 중복 제거)
     */
    public record GpuNodeScope(boolean admin, Set<String> roles, List<GpuNode> nodes) {
        public GpuNodeScope {
            roles = Set.copyOf(roles);
            nodes = List.copyOf(nodes);
        }

        public Map<String, GpuNode> byInstance() {
            Map<String, GpuNode> map = new LinkedHashMap<>();
            nodes.forEach(n -> map.put(n.instance(), n));
            return map;
        }

        public Set<String> mdcIds() {
            return nodes.stream().map(GpuNode::mdcId).filter(m -> m != null).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private final AuthService authService;
    private final RoleAssetRepository roleAssets;
    private final MonitoringTargetRepository targets;

    public GpuNodeScopeService(AuthService authService, RoleAssetRepository roleAssets, MonitoringTargetRepository targets) {
        this.authService = authService;
        this.roleAssets = roleAssets;
        this.targets = targets;
    }

    /** 현재 토큰 역할의 스코프에 역할·배포영역 필터를 적용한다. 필터가 비면 좁히지 않는다 */
    @Transactional(readOnly = true)
    public GpuNodeScope currentScope(Collection<String> roleFilter, Collection<String> deploymentFilter) {
        return scopeOf(authService.currentUserRoleIds(), roleFilter, deploymentFilter);
    }

    @Transactional(readOnly = true)
    public GpuNodeScope scopeOf(Set<String> tokenRoles, Collection<String> roleFilter, Collection<String> deploymentFilter) {
        boolean admin = tokenRoles.contains(ADMIN_ROLE);
        Set<String> roles = new LinkedHashSet<>(tokenRoles);
        if (roleFilter != null && !roleFilter.isEmpty()) {
            roles.retainAll(Set.copyOf(roleFilter));
        }
        if (roles.isEmpty()) {
            return new GpuNodeScope(admin, roles, List.of());
        }

        Map<Long, Asset> assets = new LinkedHashMap<>();
        Map<Long, Set<String>> rolesByAsset = new LinkedHashMap<>();
        for (RoleAsset mapping : roleAssets.findByRole_NameIn(roles)) {
            Asset asset = mapping.getAsset();
            if (asset == null || !PhysicalTreeBuilder.HOST_TYPES.contains(asset.getType())) {
                continue;
            }
            if (deploymentFilter != null && !deploymentFilter.isEmpty() && !deploymentFilter.contains(asset.getMdcId())) {
                continue;
            }
            assets.put(asset.getId(), asset);
            rolesByAsset.computeIfAbsent(asset.getId(), k -> new TreeSet<>()).add(mapping.getRole().getName());
        }
        if (assets.isEmpty()) {
            return new GpuNodeScope(admin, roles, List.of());
        }

        Map<String, GpuNode> byInstance = new LinkedHashMap<>();
        for (MonitoringTarget target : targets.findByAsset_IdIn(assets.keySet())) {
            if (target.getExporterType() != ExporterType.DCGM_EXPORTER) {
                continue;
            }
            Asset asset = assets.get(target.getAsset().getId());
            byInstance.putIfAbsent(target.getScrapeEndpoint(), new GpuNode(asset.getId(), asset.getName(), asset.getMdcId(),
                    target.getScrapeEndpoint(), rolesByAsset.get(asset.getId())));
        }
        return new GpuNodeScope(admin, roles, List.copyOf(byInstance.values()));
    }

    /** 등록된 모든 DCGM 수집 대상의 instance — 미등록 노드(Thanos 에는 있으나 원장에 없음) 판정용, 역할과 무관 */
    @Transactional(readOnly = true)
    public Set<String> registeredInstances() {
        return targets.findByExporterType(ExporterType.DCGM_EXPORTER).stream()
                .map(MonitoringTarget::getScrapeEndpoint)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
