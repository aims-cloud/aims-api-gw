package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

/** GPU 지표 수집 상태 — 수집 상태 상세 패널의 GPU별 카드 배지. */
public enum GpuCollectState {
    HEALTHY,    // 정상 수집
    FAILED,     // 일부/전체 지표 수집 실패
    NO_DATA     // 수집 데이터 없음 (마지막 수집·지표 수 null)
}
