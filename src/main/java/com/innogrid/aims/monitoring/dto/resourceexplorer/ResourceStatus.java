package com.innogrid.aims.monitoring.dto.resourceexplorer;

/**
 * 노드·가상머신 공통 상태 4종 — 전체 자원/역할 패널의 KPI·도넛·상태 스택바에 사용한다.
 * 목록 필터용 NodeStatus(3종)에 PENDING 을 합류시킬지는 TODO:: 확정 필요.
 */
public enum ResourceStatus {
    RUNNING, ERROR, PENDING, STOP
}
