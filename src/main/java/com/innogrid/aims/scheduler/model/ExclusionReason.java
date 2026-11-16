package com.innogrid.aims.scheduler.model;

/**
 * 노드가 후보에서 빠진 <b>까닭</b>을 기계가 읽는 이름으로 나눈 것.
 *
 * <p>지금까지 제외 사유는 사람이 읽는 한국어 문장 하나뿐이었다. 그래서 "열 때문에 빠진 후보가 몇인가"
 * 같은 질문에 답하려면 문장을 부분 검색해야 했고, 문구를 한 글자만 고쳐도 집계가 조용히 어긋났다.
 * 이 열거형이 그 자리를 대신한다 — <b>문장은 그대로 두고</b> 옆에 이름을 하나 더 얹는다.</p>
 *
 * <p>이름은 제약 묶음(C1~C6)이 아니라 <b>까닭</b> 단위다. 같은 묶음 안에서도 사람이 다르게 조치해야 하면
 * 나눈다 — 자원 부족과 디스크 부족은 둘 다 C1 이지만 늘려야 할 것이 다르다. 반대로 이름을 늘릴 때는
 * "이 이름으로 무엇을 따로 세거나 다르게 조치할 수 있는가" 를 먼저 묻는다.</p>
 *
 * <p>이름은 응답에 실리므로 <b>바꾸면 계약이 깨진다</b>. 새 제약이 생기면 이름을 더하고, 기존 이름의
 * 철자는 고치지 않는다.</p>
 */
public enum ExclusionReason {

    /** C1 — 요구한 CPU·메모리·GPU 를 노드가 대지 못한다. */
    INSUFFICIENT_RESOURCE,

    /** C1 — 디스크 총량을 아는 노드인데 요구한 용량이 남지 않았다. */
    INSUFFICIENT_DISK,

    /** C2 — 실효 CPU 사용률이 정책 상한을 넘었다. */
    CPU_USAGE_EXCEEDED,

    /** C2 — 실효 메모리 사용률이 정책 상한을 넘었다. */
    MEMORY_USAGE_EXCEEDED,

    /** C3 — 임계 온도를 넘겨 냉각 대기 중이다. 시간이 지나면 저절로 풀린다. */
    COOLING_DOWN,

    /** C3 — 실측 온도가 정책 임계를 넘었다. */
    TEMPERATURE_EXCEEDED,

    /** C3 — 워크로드가 지정한 온도 상한을 노드가 넘었다. 정책과 무관하게 걸린다. */
    WORKLOAD_TEMPERATURE_EXCEEDED,

    /** C3 — 지금은 임계 아래지만 이 워크로드를 얹으면 넘는다. */
    PREDICTED_TEMPERATURE_EXCEEDED,

    /** C4 — 실효 전력 여유율이 정책이 요구하는 만큼 남지 않았다. */
    POWER_HEADROOM_EXCEEDED,

    /** C4 — 워크로드가 지정한 전력 상한을 노드가 넘었다. 정책과 무관하게 걸린다. */
    WORKLOAD_POWER_EXCEEDED,

    /** C4 — 스케줄 실행 단위로 지정한 전력 예산을 노드가 넘었다. */
    RUN_POWER_BUDGET_EXCEEDED,

    /** C4 — 지금은 용량 아래지만 이 워크로드를 얹으면 넘는다. */
    PREDICTED_POWER_EXCEEDED,

    /** C5 — 데이터가 있는 MDC 와 노드가 있는 MDC 사이 링크가 끊겼다. */
    MDC_LINK_DOWN,

    /** C6 — 노드가 속한 MDC 전체의 예상 전력 합계가 예산을 넘었다. */
    MDC_POWER_BUDGET_EXCEEDED;

    /** 열 때문에 빠진 것인가 — 냉각 대기·실측 초과·워크로드 상한·배치 후 예상을 묶는다. */
    public boolean isThermal() {
        return this == COOLING_DOWN
                || this == TEMPERATURE_EXCEEDED
                || this == WORKLOAD_TEMPERATURE_EXCEEDED
                || this == PREDICTED_TEMPERATURE_EXCEEDED;
    }

    /** 전력 때문에 빠진 것인가 — 여유율·워크로드 상한·실행 예산·배치 후 예상·MDC 예산을 묶는다. */
    public boolean isPower() {
        return this == POWER_HEADROOM_EXCEEDED
                || this == WORKLOAD_POWER_EXCEEDED
                || this == RUN_POWER_BUDGET_EXCEEDED
                || this == PREDICTED_POWER_EXCEEDED
                || this == MDC_POWER_BUDGET_EXCEEDED;
    }
}
