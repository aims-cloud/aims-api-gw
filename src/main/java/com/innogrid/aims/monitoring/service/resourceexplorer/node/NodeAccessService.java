package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.repository.AssetRepository;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 모든 노드 패널에서 동일한 역할 스코프 검증을 수행한다. */
@Component
public class NodeAccessService {
    private final ResourceScopeService scopes;
    private final AssetRepository assets;

    public NodeAccessService(ResourceScopeService scopes, AssetRepository assets) {
        this.scopes = scopes;
        this.assets = assets;
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

    private static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "노드를 찾을 수 없습니다.");
    }
}
