package com.innogrid.aims.monitoring.dto.resourceexplorer.tree;

/**
 * 모니터링 항목의 수집 상태 (docs/06 자원 탐색기 트리 설계 §9.4).
 * 자원 실행 상태(RUNNING 등)와 분리해 관리한다 — 자원이 RUNNING 이어도 exporter 가 없으면 NOT_CONFIGURED 다.
 */
public enum MonitoringStatus {
    /** 해당 exporter 가 자원에 등록되지 않음 — 관련 항목을 생성하지 않는다 */
    NOT_CONFIGURED,
    /** exporter 등록 + up=1 + 최근 샘플이 유효 시간 안에 존재 */
    COLLECTING,
    /** exporter 는 등록되어 있으나 up=0 또는 최근 샘플이 유효 시간을 초과 — 항목은 유지하고 미수집으로 표시 */
    UNAVAILABLE,
    /** exporter 는 정상이나 특정 장치·센서 메트릭이 한 번도 확인되지 않음 — 세부 항목을 생성하지 않는다 */
    NO_DATA
}
