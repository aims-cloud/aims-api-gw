package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.util.List;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.repository.AssetRepository;
import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;
import com.innogrid.aims.monitoring.repository.MonitoringTargetRepository;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.springframework.stereotype.Component;

/** 모든 노드 패널에서 동일한 역할 스코프 검증을 수행하고, 노드의 exporter 선택자를 만든다. */
@Component
public class NodeAccessService {
    private final ResourceScopeService scopes;
    private final AssetRepository assets;
    private final MonitoringTargetRepository targets;

    public NodeAccessService(ResourceScopeService scopes, AssetRepository assets, MonitoringTargetRepository targets) {
        this.scopes = scopes;
        this.assets = assets;
        this.targets = targets;
    }

    public record AccessibleNode(Asset asset, ScopedHost host) {}

    public AccessibleNode require(String nodeId) {
        long id;
        try { id = Long.parseLong(nodeId); }
        catch (NumberFormatException e) { throw notFound(); }
        ScopedHost host = scopes.currentUserScope().distinctHosts().stream()
                .filter(h -> h.assetId() == id).findFirst().orElseThrow(NodeAccessService::notFound);
        return new AccessibleNode(assets.findById(id).orElseThrow(NodeAccessService::notFound), host);
    }

    /** 스코프 검증(404) 뒤 node_exporter 선택자. 수집 대상 주소가 없으면 null — 호출 측은 조회 없이 미수집으로 처리한다 */
    public MetricSelector selector(String nodeId) {
        return selectorOf(require(nodeId).host());
    }

    /** 스코프 검증(404) 뒤 dcgm_exporter 선택자. 등록된 수집 대상이 없으면 null — 호출 측은 조회 없이 미수집으로 처리한다 */
    public MetricSelector dcgmSelector(String nodeId) {
        return dcgmSelectorOf(require(nodeId).host());
    }

    /** 검증을 이미 마친 호스트의 node_exporter 선택자(instance 기준, 없으면 null) */
    public static MetricSelector selectorOf(ScopedHost host) {
        return host.instance() == null ? null : MetricSelector.instance(host.instance());
    }

    /** 검증을 이미 마친 호스트의 dcgm_exporter 선택자 — 자산에 등록된 DCGM 수집 대상의 scrape 주소, 없으면 null */
    public MetricSelector dcgmSelectorOf(ScopedHost host) {
        return targets.findByAsset_IdIn(List.of(host.assetId())).stream()
                .filter(t -> t.getExporterType() == ExporterType.DCGM_EXPORTER)
                .map(MonitoringTarget::getScrapeEndpoint)
                .findFirst()
                .map(MetricSelector::instance)
                .orElse(null);
    }

    private static BusinessException notFound() {
        return BusinessException.notFound("노드를 찾을 수 없습니다.");
    }
}
