package com.innogrid.aims.scheduler.dto;

import java.util.List;

/**
 * 배치 제약 조건 설명 — 명세 A1(제약 조건 기반 스케줄링)이 실제로 무엇을 검사하는지
 * 밖에서 확인할 수 있게 한다.
 *
 * <p>화면 요구는 없다. 어떤 제약이 어떤 순서로 적용되는지 코드를 열지 않고 확인할
 * 수단이 없어 만들었다. 운영 중 배치가 계속 실패할 때 어느 단계에서 걸리는지 좁히는
 * 데도 쓴다.</p>
 */
public record ConstraintDescriptionResponse(
        String code,            // C0 ~ C6
        String name,
        String description,
        String source,          // 임계값의 출처 (정책 필드 / 워크로드 필드 / 설정)
        boolean togglable       // 정책에서 켜고 끌 수 있는지
) {
    /** 현재 구현된 제약 파이프라인. 평가 순서대로다. C0 은 제약 평가 이전, 수집 단계에서 걸러진다. */
    public static List<ConstraintDescriptionResponse> all() {
        return List.of(
                new ConstraintDescriptionResponse("C0", "핵심 메트릭 수집",
                        "CPU 사용률·코어 수·메모리 총량·가용량 중 하나라도 조회 실패나 시리즈 없음으로 알 수 없는 "
                                + "노드는 후보 목록에 넣지 않는다. 0 으로 채우면 빈 노드로 오판되기 때문이다. "
                                + "제외된 노드는 진단 응답의 excludedNodes 와 배치 실패 사유에 표기된다.",
                        "메트릭 저장소 응답 (설정의 node-job 으로 대상 노드 선택)", false),
                new ConstraintDescriptionResponse("C1", "자원 가용성",
                        "예약분을 뺀 가용 CPU·메모리·GPU 가 워크로드 요구량 이상이어야 한다. "
                                + "같은 실행에서 앞서 배치된 워크로드의 요구량도 예약으로 잡혀 있다. "
                                + "디스크(requiredDiskGb)는 노드의 파일시스템 총량을 알 때만 검사하고, 총량을 모르면 건너뛴다. "
                                + "GPU(requiredGpu)는 워크로드가 요구할 때만 검사한다.",
                        "워크로드의 requiredCpu / requiredMemoryMb / requiredGpu / requiredDiskGb", false),
                new ConstraintDescriptionResponse("C2", "사용률 임계",
                        "노드의 현재 CPU·메모리 사용률이 정책 임계치를 넘으면 제외한다.",
                        "정책의 maxCpuUsagePercent / maxMemoryUsagePercent", false),
                new ConstraintDescriptionResponse("C3", "열 상태",
                        "냉각 대기 중이거나, 현재 온도가 임계치를 넘거나, 워크로드가 지정한 온도 제약을 "
                                + "넘거나, 배치 후 예상 온도가 임계치를 넘으면 제외한다. "
                                + "임계 온도를 넘긴 노드는 일정 시간 냉각 대기로 잡아 둔다. "
                                + "냉각 대기는 그 대기를 건 임계치와 같거나 더 낮은 임계치로 평가할 때만 노드를 제외한다.",
                        "정책의 maxTemperatureCelsius / warningTemperatureCelsius, 워크로드의 maxTemperatureCelsius",
                        true),
                new ConstraintDescriptionResponse("C4", "전력",
                        "전력 여유율이 정책 기준에 못 미치거나, 워크로드가 지정한 전력 예산을 넘거나, "
                                + "배치 후 예상 전력이 노드 용량을 넘으면 제외한다.",
                        "정책의 maxPowerUsagePercent, 워크로드의 maxPowerBudgetWatts, 설정의 전력 용량",
                        true),
                new ConstraintDescriptionResponse("C5", "MDC 링크",
                        "정책이 affinityEnabled 이고 워크로드에 dataMdcId 가 있을 때만 검사한다. 데이터 MDC 와 노드 MDC 사이의 "
                                + "링크(MDC 링크 원장)가 DOWN 이면 제외한다. 같은 MDC·미등록 쌍은 통과하고, UP·DEGRADED 의 "
                                + "지연·손실은 제약이 아니라 weighted 엔진의 네트워크 점수에 반영된다.",
                        "정책의 affinityEnabled, 워크로드의 dataMdcId, MDC 링크 원장(/scheduler/mdc-links)의 status",
                        true),
                new ConstraintDescriptionResponse("C6", "MDC 전력 예산",
                        "노드가 속한 MDC 의 전력 합계가 예산을 넘으면 제외한다. 합계는 실측이 아니라 예약분을 반영한 "
                                + "실효 전력이라 같은 실행에서 배치가 쌓일수록 올라간다. 예산이 등록되지 않은 MDC 와 전력을 "
                                + "수집하지 못한 노드는 검사하지 않으며, 미계측 형제 노드는 합계에서 빼고 제외 사유에 대수를 적는다. "
                                + "노드 한 대의 정격을 보는 C4 와 달리 MDC 한 곳의 총량을 본다.",
                        "MDC 전력 예산 원장(/scheduler/mdc-power-budgets)의 budgetWatts",
                        false));
    }
}
