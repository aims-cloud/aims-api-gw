package com.innogrid.aims.monitoring.dto;

import java.util.List;

/** 자원 탐색기 트리 조회 조건. */
public record ResourceTreeFilter(
        List<String> roleIds,
        List<String> deploymentIds,
        String keyword
) {
    public ResourceTreeFilter {
        roleIds = roleIds == null ? List.of() : List.copyOf(roleIds);
        deploymentIds = deploymentIds == null ? List.of() : List.copyOf(deploymentIds);
        keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
    }
}
