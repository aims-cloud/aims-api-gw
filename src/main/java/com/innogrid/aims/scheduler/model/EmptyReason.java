package com.innogrid.aims.scheduler.model;

/**
 * 대상 0건의 사유. 실행이 메시지를 고르는 우선순위와 같다 — 소진 &gt; 대기 &gt; 없음.
 *
 * <p>실행 미리보기 · 모의 실행 응답의 {@code emptyReason}과 스케줄 실행의 대상 없음 판정이 함께 쓴다.</p>
 */
public enum EmptyReason {
    /** 대상이 될 워크로드가 없다(정상) */
    NONE,
    /** 재시도 간격이 지나지 않은 워크로드만 있다(시간이 지나면 풀림) */
    WAITING,
    /** 재시도 상한에 걸린 워크로드가 있다(일괄 재개가 필요 · 실행은 FAILED로 기록) */
    EXHAUSTED
}
