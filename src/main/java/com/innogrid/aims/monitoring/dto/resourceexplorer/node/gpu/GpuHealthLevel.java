package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

/** GPU 상태 요약의 판정 단계 — 배너와 카드 하이라이트에 사용한다. */
public enum GpuHealthLevel {
    NORMAL,     // 정상
    WARNING,    // 일부 이상 감지 (노란 하이라이트)
    CRITICAL    // 이상 감지 (빨간 하이라이트)
}
