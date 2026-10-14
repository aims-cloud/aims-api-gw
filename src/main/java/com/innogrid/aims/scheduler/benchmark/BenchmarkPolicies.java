package com.innogrid.aims.scheduler.benchmark;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;

/**
 * 비교용 정책 변종 — <b>저장된 정책을 건드리지 않고</b> 판을 바꾼다.
 *
 * <p>자원 전용 대조군 두 종은 별도의 배치 규칙이 없다. 우리 {@code weighted} 엔진에 <b>전력·온도·
 * 네트워크 가중치를 0 으로 만든 정책</b>을 물리면 그대로 자원 전용 스케줄러가 된다 — 대조군을 새로
 * 구현하지 않는 것이 요점이다. 같은 코드에 정책만 달리 준 것이므로, 두 열의 차이는 알고리즘 차이가
 * 아니라 <b>정책이 무엇을 보게 했는지</b>의 차이라고 말할 수 있다.</p>
 *
 * <p><b>사본은 영속되지 않는다.</b> 변종의 출발점은 {@link SchedulingPolicy#detachedCopy()} 가 만든 사본이다 —
 * {@code id} 세터가 없어 사본의 {@code id} 는 항상 {@code null} 이고, JPA 가 이것을 기존 행과 동일시할 경로가 없다.
 * 사본을 저장할 코드도 없다 — 비교 하네스는 정책 저장소를 쓰지 않는다. 스케줄이 지정한 온도 한계를
 * 이번 실행에만 적용하는 {@code ScheduleExecutionService.applyOverrides} 도 같은 사본을 쓴다.</p>
 *
 * <p><b>필드를 하나 빠뜨리면 게이트가 조용히 꺼진다.</b> 실제로 예전 실행 경로의 손 복사에서 {@code affinityEnabled}
 * 가 빠져 있어 온도 한계를 지정한 스케줄에서만 MDC 링크 게이트가 꺼지던 결함이 있었다. 여기서 그런
 * 일이 나면 "대조군이 제약을 덜 받아 유리했다"가 되어 비교 자체가 무효가 되므로,
 * {@code SchedulingPolicyCopyTest} 가 선언된 필드 전량을 반사로 훑어 고정한다.</p>
 */
public final class BenchmarkPolicies {

    private BenchmarkPolicies() {
    }

    /**
     * 전력·온도·네트워크 가중치 0. {@code keepGates=false} 면 powerAware·temperatureAware 플래그까지 끈다.
     *
     * <p><b>CPU·메모리 가중치는 그대로 둔다.</b> 다섯 개를 다 0 으로 만들면 모든 노드의 총점이 같아지고,
     * {@code weighted} 엔진은 동점에서 첫 후보를 남기므로 결과가 선착순(FFD)과 같아진다 — 대조군이
     * 두 개가 아니라 하나가 되는 셈이고, "자원만 보게 했다"는 판이 아니라 "아무것도 보지 않게 했다"는
     * 판이 된다.</p>
     *
     * <p><b>가중치와 게이트를 나눈 이유.</b> 가중치 0 은 점수에서 전력·온도를 빼는 것이고, 게이트를
     * 끄는 것은 제약(C3·C4)에서까지 빼는 것이다. 우리 기여가 "점수식"인지 "제약"인지 분리하려면 두
     * 판이 따로 있어야 한다 — 한꺼번에 끄면 어느 쪽 효과인지 말할 수 없다.</p>
     *
     * <p>{@code affinityEnabled} 은 건드리지 않는다. 그것은 MDC 링크라는 다른 축의 게이트이고, 판을
     * 만드는 쪽이 정한 값을 대조군이 마음대로 바꾸면 후보 집합 자체가 달라진다.</p>
     */
    public static SchedulingPolicy resourceOnly(SchedulingPolicy base, boolean keepGates) {
        SchedulingPolicy copy = base.detachedCopy();

        copy.setPowerWeight(0);
        copy.setTemperatureWeight(0);
        copy.setNetworkWeight(0);

        if (!keepGates) {
            copy.setPowerAwareEnabled(false);
            copy.setTemperatureAwareEnabled(false);
        }
        return copy;
    }
}
