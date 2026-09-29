package com.innogrid.aims.monitoring.dto.resourceexplorer.node.service;

import java.time.OffsetDateTime;
import com.innogrid.aims.monitoring.entity.AgentTransport;

/**
 * 노드 로그/트레이스 탭 — 이 노드의 수집 에이전트에 원장 목록이 반영됐는지(docs/11 §5.3).
 * 서비스 목록과 별도 API 로 내려 준다. 화면은 NO_AGENT 면 "에이전트 미구성", PENDING 이면 "반영 대기", FAILED 면 message 를 보여 준다.
 */
public record AgentTargetsStatusResponse(
        AgentTransport transport,        // NONE 이면 목록을 전송하지 않는 노드
        TargetsStatus targetsStatus,
        OffsetDateTime appliedAt,        // 마지막 반영 시각. 없으면 null
        int targetCount,                 // 원장에서 계산한 동적 수집 파일 수(정적 4개 제외)
        String message                   // FAILED 사유. 그 외 null
) {
    public enum TargetsStatus { NO_AGENT, APPLIED, PENDING, FAILED }
}
