package com.innogrid.aims.monitoring.dto.resourceexplorer.node.process;

import java.util.List;

/**
 * Process 카테고리 개요 탭의 KPI 카드 — 실행 중·블록 프로세스 수, 전체 프로세스·스레드 수, fork 발생률(최근 5분 rate, /s),
 * 상태별 프로세스 수(R/S/D/Z/T …).
 * 실행 중·블록·fork 는 기본 콜렉터라 항상 있고, 전체 프로세스·스레드·상태별은 {@code --collector.processes} 가 켜진 노드에서만 값이 있다.
 * 미수집이면 null(상태별은 빈 목록) — 화면에는 "-" 로 표시한다.
 */
public record ProcessSummaryResponse(
        Integer runningCount,
        Integer blockedCount,
        Integer processCount,
        Integer threadCount,
        Double forksPerSec,
        List<StateCount> processesByState
) {
    /** 상태별 프로세스 수 — state 는 node_exporter 라벨 그대로(R 실행, S 대기, D 디스크 대기, Z 좀비, T 정지 …) */
    public record StateCount(String state, int count) {}
}
