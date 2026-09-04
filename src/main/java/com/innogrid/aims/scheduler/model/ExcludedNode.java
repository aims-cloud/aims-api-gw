package com.innogrid.aims.scheduler.model;

import java.util.List;

/**
 * 수집 단계에서 후보에 넣지 않은 노드.
 *
 * <p>두 가지 사유가 있다. <b>핵심 메트릭 판단 불가</b> — CPU 사용률·코어 수·MemTotal·MemAvailable 을 질의 실패나
 * 시리즈 없음으로 알 수 없는 노드(0 으로 채우면 "빈 노드"로 오판). <b>질의 응답 없음</b> — 저장소가 상한 안에 답하지
 * 않아 기다리기를 멈춘 노드(단계 14). 어느 쪽이든 로그에만 남기면 클러스터 진단의 노드 수가 조용히 줄어들므로
 * 진단 응답이 "N대를 왜 뺐는지" 말할 수 있게 여기에 담는다. 응답 DTO 는 {@link #reason()} 문자열만 노출한다.</p>
 *
 * @param missingMetrics 알 수 없었던 메트릭 이름(판단 불가일 때). 예: {@code [cpu사용률, 코어수]}. 응답 없음이면 빈 목록
 * @param cause          제외 사유 종류
 * @param timeoutMs      응답 없음일 때 적용된 상한(ms). 판단 불가면 0
 */
public record ExcludedNode(String mdcId, String nodeId, List<String> missingMetrics, Cause cause, long timeoutMs) {

    /** 제외 사유 종류. */
    public enum Cause { MISSING_METRICS, TIMEOUT }

    public ExcludedNode {
        missingMetrics = missingMetrics == null ? List.of() : List.copyOf(missingMetrics);
        cause = cause == null ? Cause.MISSING_METRICS : cause;
    }

    /** 핵심 메트릭 판단 불가(기존 형태). */
    public ExcludedNode(String mdcId, String nodeId, List<String> missingMetrics) {
        this(mdcId, nodeId, missingMetrics, Cause.MISSING_METRICS, 0);
    }

    /** 저장소가 상한 안에 답하지 않아 기다리기를 멈춘 노드. */
    public static ExcludedNode timedOut(String mdcId, String nodeId, long timeoutMs) {
        return new ExcludedNode(mdcId, nodeId, List.of(), Cause.TIMEOUT, timeoutMs);
    }

    public boolean isTimeout() {
        return cause == Cause.TIMEOUT;
    }

    /** 사람이 읽는 사유 한 줄. */
    public String reason() {
        if (cause == Cause.TIMEOUT) {
            return String.format("메트릭 질의 응답 없음 (상한 %dms 초과 — 저장소가 응답하지 않아 기다리기를 멈춤)", timeoutMs);
        }
        return "핵심 메트릭 판단 불가: " + String.join(", ", missingMetrics) + " (질의 실패 또는 시리즈 없음)";
    }
}
