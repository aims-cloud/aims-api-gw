package com.innogrid.aims.monitoring.dto.resourceexplorer.tree;

/** 검색된 자원의 상위 경로 항목. */
public record ResourceTreePathResponse(
        String id,
        String name,
        ResourceTreeNodeType type
) {
}

