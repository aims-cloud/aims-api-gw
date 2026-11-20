package com.innogrid.aims.scheduler.dto;

import com.innogrid.aims.scheduler.model.ExcludedNode;

/**
 * 진단 응답에 실리는 "수집 단계에서 제외된 노드".
 *
 * <p>핵심 메트릭(CPU 사용률·코어 수·메모리)을 알 수 없어 후보에 넣지 않은 노드다. 진단의
 * {@code totalNodes}는 판단 가능한 노드만 세므로, 이 목록이 없으면 "10대 중 1대 제외"가
 * "9대 클러스터"로 보인다.</p>
 *
 * @param reason 사람이 읽는 사유. 예: {@code 핵심 메트릭 판단 불가: cpu사용률 (질의 실패 또는 시리즈 없음)}
 */
public record ExcludedNodeResponse(String mdcId, String nodeId, String reason) {

    public static ExcludedNodeResponse from(ExcludedNode excluded) {
        return new ExcludedNodeResponse(excluded.mdcId(), excluded.nodeId(), excluded.reason());
    }
}
