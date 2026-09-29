package com.innogrid.aims.monitoring.entity;

/** 수집 대상 목록(log-targets.json)을 노드에 전달하는 방식 */
public enum AgentTransport {
    /** 전달하지 않음 — 에이전트 미구성 노드 */
    NONE,
    /** 백엔드가 같은 서버의 파일을 직접 쓴다(백엔드와 Alloy 가 한 호스트일 때) */
    LOCAL,
    /** 백엔드가 SSH 로 노드에 쓴다(에이전트 매니저 구현 예정) */
    SSH
}
