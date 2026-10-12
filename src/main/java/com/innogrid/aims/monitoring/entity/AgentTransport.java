package com.innogrid.aims.monitoring.entity;

/** 수집 대상 목록(log-targets.json)을 노드에 전달하는 방식 */
public enum AgentTransport {
    /** 전달하지 않음 — 에이전트 미구성 노드 */
    NONE,
    /** 백엔드가 같은 서버의 파일을 직접 쓴다(백엔드와 Alloy 가 한 호스트일 때) */
    LOCAL,
    /**
     * 백엔드가 SSH 로 노드에 쓴다. 2026-09-30 책임님 답변(세부1 노드에 접근하지 않음, 접속 정보 불필요)으로 구현 계획이 없어졌다.
     * DB 의 기존 값과 호환을 위해 열거만 남긴다.
     */
    SSH,
    /**
     * 노드의 Alloy 가 백엔드 목록 API({@code GET /api/v1/agent/targets?instance=host:port})를 주기적으로 가져간다(pull).
     * 노드는 자기 node_exporter 주소(instance 라벨)로 식별하며 별도 토큰은 없다(사내 대역 전제).
     * 백엔드가 노드에 접근하지 않는 세부1 물리 노드용 — 세부1 이 우리가 준 설정으로 Alloy 를 설치만 하면 된다(deploy/alloy-node).
     */
    PULL
}
