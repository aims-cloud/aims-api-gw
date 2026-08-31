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
        String code,            // C1 ~ C4
        String name,
        String description,
        String source,          // 임계값의 출처 (정책 필드 / 워크로드 필드 / 설정)
        boolean togglable       // 정책에서 켜고 끌 수 있는지
) {
    /** 현재 구현된 제약 파이프라인. 평가 순서대로다. */
    public static List<ConstraintDescriptionResponse> all() {
        return List.of(
                new ConstraintDescriptionResponse("C1", "자원 가용성",
                        "예약분을 뺀 가용 CPU·메모리·GPU 가 워크로드 요구량 이상이어야 한다. "
                                + "같은 실행에서 앞서 배치된 워크로드의 요구량도 예약으로 잡혀 있다.",
                        "워크로드의 requiredCpu / requiredMemoryMb / requiredGpu", false),
                new ConstraintDescriptionResponse("C2", "사용률 임계",
                        "노드의 현재 CPU·메모리 사용률이 정책 임계치를 넘으면 제외한다.",
                        "정책의 maxCpuUsagePercent / maxMemoryUsagePercent", false),
                new ConstraintDescriptionResponse("C3", "열 상태",
                        "냉각 대기 중이거나, 현재 온도가 임계치를 넘거나, 워크로드가 지정한 온도 제약을 "
                                + "넘거나, 배치 후 예상 온도가 임계치를 넘으면 제외한다. "
                                + "임계 온도를 넘긴 노드는 일정 시간 냉각 대기로 잡아 둔다.",
                        "정책의 maxTemperatureCelsius / warningTemperatureCelsius, 워크로드의 maxTemperatureCelsius",
                        true),
                new ConstraintDescriptionResponse("C4", "전력",
                        "전력 여유율이 정책 기준에 못 미치거나, 워크로드가 지정한 전력 예산을 넘거나, "
                                + "배치 후 예상 전력이 노드 용량을 넘으면 제외한다.",
                        "정책의 maxPowerUsagePercent, 워크로드의 maxPowerBudgetWatts, 설정의 전력 용량",
                        true));
    }
}
