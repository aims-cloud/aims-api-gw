package com.innogrid.aims.monitoring.dto.resourceexplorer.tree;

import java.util.List;

/** 자원 탐색기 트리 조회 조건. 역할은 사용자 토큰에서 추출하므로 roleIds 는 서비스 내부용이다. */
public record ResourceTreeFilter(
        List<String> roleIds,
        List<String> mdcIds,
        String keyword
) {
    public ResourceTreeFilter {
        roleIds = roleIds == null ? List.of() : List.copyOf(roleIds);
        mdcIds = mdcIds == null ? List.of() : List.copyOf(mdcIds);
        keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
    }
}
