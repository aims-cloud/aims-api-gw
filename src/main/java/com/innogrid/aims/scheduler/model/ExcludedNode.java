package com.innogrid.aims.scheduler.model;

import java.util.List;

/**
 * 수집 단계에서 후보에 넣지 않은 노드.
 *
 * <p>핵심 메트릭(CPU 사용률·코어 수·MemTotal·MemAvailable)을 질의 실패나 시리즈 없음으로 알 수 없는
 * 노드다. 0 으로 채우면 "빈 노드"로 오판되므로 수집기가 목록에서 빼는데, 그 사실을 로그에만 남기면
 * 클러스터 진단의 노드 수가 조용히 줄어든다. 진단 응답이 "N대를 왜 뺐는지" 말할 수 있게 여기에 담는다.</p>
 *
 * @param missingMetrics 알 수 없었던 메트릭 이름. 예: {@code [cpu사용률, 코어수]}
 */
public record ExcludedNode(String mdcId, String nodeId, List<String> missingMetrics) {

    public ExcludedNode {
        missingMetrics = missingMetrics == null ? List.of() : List.copyOf(missingMetrics);
    }

    /** 사람이 읽는 사유 한 줄. */
    public String reason() {
        return "핵심 메트릭 판단 불가: " + String.join(", ", missingMetrics) + " (질의 실패 또는 시리즈 없음)";
    }
}
