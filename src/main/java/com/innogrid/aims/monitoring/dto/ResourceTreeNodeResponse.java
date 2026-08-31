package com.innogrid.aims.monitoring.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 자원 탐색기에서 사용하는 공통 트리 항목. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceTreeNodeResponse(
        String id,
        String parentId,
        String name,
        ResourceTreeNodeType type,
        String resourceId,
        String roleId,
        String deploymentId,
        String status,
        boolean leaf,
        int childCount,
        List<ResourceTreeNodeResponse> children
) {
    public ResourceTreeNodeResponse {
        children = children == null ? null : List.copyOf(children);
    }
}
