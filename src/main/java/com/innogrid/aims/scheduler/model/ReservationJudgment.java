package com.innogrid.aims.scheduler.model;

/**
 * 배치 대기 · 실행 중 워크로드 하나를 노드 예약으로 얼마나 잡는지에 대한 판정.
 *
 * <p>배치 컨텍스트의 예약 반영과 노드별 예약 내역 조회가 같은 판정과 같은 예약량 식을 쓴다. 예약량 식은
 * {@link NodeResource#reserve(int, long, int, long)}와 같다(디스크 음수는 0).</p>
 */
public enum ReservationJudgment {
    /**
     * CPU · 메모리 · GPU · 디스크를 모두 예약한다. 배치 대기는 배치 시각이 보존 기한(TTL) 안이거나 모를 때, 실행 중은
     * 시작한 지(시작 기록이 없으면 배치 시각부터) 메트릭 지연 시간이 안 지났거나 두 시각을 모두 모를 때다
     */
    FULL,
    /**
     * GPU 개수만 예약한다. 실행 중이고 시작한 지(시작 기록이 없으면 배치 시각부터) 메트릭 지연 시간이 지났다.
     * CPU · 메모리 · 디스크는 사용률 메트릭에 있다
     */
    GPU_ONLY,
    /** 아무것도 예약하지 않는다. 배치 대기인데 배치 시각이 보존 기한(TTL)보다 오래됐다 */
    EXPIRED,
    /** 아무것도 예약하지 않는다. 배정 노드가 없다 */
    UNASSIGNED;

    /** 이 판정으로 예약하는 CPU. */
    public int cpu(int required) {
        return this == FULL ? required : 0;
    }

    /** 이 판정으로 예약하는 메모리(MB). */
    public long memoryMb(long required) {
        return this == FULL ? required : 0;
    }

    /** 이 판정으로 예약하는 GPU 개수. 실행 중인 워크로드의 카드 점유는 사용률 메트릭에 드러나지 않아 GPU_ONLY도 남긴다. */
    public int gpu(int required) {
        return this == FULL || this == GPU_ONLY ? required : 0;
    }

    /** 이 판정으로 예약하는 디스크(GB). 음수는 0으로 자른다. */
    public long diskGb(long required) {
        return this == FULL ? Math.max(0, required) : 0;
    }
}
