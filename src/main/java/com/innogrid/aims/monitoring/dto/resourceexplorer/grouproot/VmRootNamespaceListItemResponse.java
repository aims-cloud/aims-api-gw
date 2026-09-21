package com.innogrid.aims.monitoring.dto.resourceexplorer.grouproot;

import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusSummary;

/**
 * 가상머신 그룹 루트의 Namespace 목록 행 — 트리 NAMESPACE 노드({@code nsId} 필드)와 매칭한다.
 * {@code tumblebugUnavailable} 은 이 Namespace 의 Infra 조회가 실패한 채로 남아 있다는 뜻(이전 값 표시).
 */
public record VmRootNamespaceListItemResponse(
        String nsId,
        String name,
        long infraCount,
        long vmCount,
        ResourceStatusSummary vmStatus,
        boolean tumblebugUnavailable
) {
}
