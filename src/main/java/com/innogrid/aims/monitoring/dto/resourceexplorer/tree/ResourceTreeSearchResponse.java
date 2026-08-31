package com.innogrid.aims.monitoring.dto.resourceexplorer.tree;

import java.util.List;

/** 자원 트리 검색 결과와 해당 결과를 펼치기 위한 상위 경로. */
public record ResourceTreeSearchResponse(
        ResourceTreeNodeResponse node,
        List<ResourceTreePathResponse> path
) {
    public ResourceTreeSearchResponse {
        path = path == null ? List.of() : List.copyOf(path);
    }
}

