package com.innogrid.aims.scheduler.benchmark;

import java.util.Comparator;

import com.innogrid.aims.scheduler.engine.SchedulingEngine;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;

/**
 * 배치 비교에 참가하는 <b>한 전략</b> — 운영 엔진이거나 대조군이다.
 *
 * <p><b>이것은 record 이고 {@link SchedulingEngine} 을 구현하지 않는다.</b> 그게 이 설계의 핵심이다.
 * {@code EngineRegistry} 는 생성자에서 {@code List<SchedulingEngine>} 을 받아 <b>스프링 컨텍스트의
 * 모든 엔진 빈을 자동 수집</b>하고, 그 목록이 {@code GET /scheduler/engines} 로 나가며
 * {@code engineRegistry.contains()} 를 쓰는 입력 검증들의 허용 집합이 된다. 대조군이 빈이 되는 순간
 * 그 검증이 통째로 열리고, 저장된 스케줄의 엔진 값은 <b>검증 없이</b> 실행되므로 cron 이 대조군으로
 * 실제 배치를 하고 <b>지우지 않는 실행 이력</b>에 남는다. 그래서 대조군은 인터페이스를 구현하지 않고
 * 스프링 애너테이션도 하나도 붙이지 않는다 — 레지스트리가 수집할 수 있는 타입이 아니어야 한다.</p>
 *
 * <p>대신 함수형 계약({@link Placer})을 쓴다. 운영 엔진은 {@link #ofEngine} 이 메서드 참조 한 줄로
 * 감싸므로, 하네스는 엔진과 대조군을 <b>같은 타입</b>으로 돌릴 수 있다.</p>
 *
 * <p><b>예약({@code context.reserve})은 이 계약에 들어 있지 않다.</b> 운영 경로에서도 엔진은 점수만
 * 내고 예약은 호출부가 잡는다. 대조군만 스스로 예약하면 두 경로의 의미가 달라지고, 하네스가
 * 전략 종류를 보고 분기해야 하며 이중 예약이 생긴다. 배치가 확정된 뒤 예약을 잡는 것은
 * <b>하네스의 몫</b>이다.</p>
 *
 * @param id            전략 식별자. 응답의 열 이름이 된다
 * @param label         사람이 읽는 이름
 * @param kind          운영 엔진인지 대조군인지 — 표에서 두 집단을 갈라 보여줄 때 쓴다
 * @param policy        이 전략이 볼 정책 변종. 정책 사본은 {@link BenchmarkPolicies} 가 만든다
 * @param workloadOrder 워크로드를 배치할 순서. {@code null} 이면 표본 순서(도착 순서)
 * @param placer        배치 함수
 */
public record PlacementStrategy(String id, String label, Kind kind, PolicyVariant policy,
                                Comparator<Workload> workloadOrder, Placer placer) {

    /**
     * 워크로드 하나를 배치한다.
     *
     * <p>{@link SchedulingEngine#schedule(Workload, PlacementContext)} 와 <b>시그니처만</b> 같다 —
     * 상속 관계가 없어야 레지스트리에 수집되지 않는다.</p>
     */
    @FunctionalInterface
    public interface Placer {
        SchedulingResult place(Workload workload, PlacementContext context);
    }

    /** 전략의 출신. 표에서 "우리 엔진"과 "대조군"을 섞어 놓지 않기 위해 구분한다. */
    public enum Kind {
        /** 운영 코드에 있는 스케줄링 엔진을 그대로 감싼 것. */
        ENGINE,
        /** 비교를 위해 이 패키지에만 존재하는 대조군. 운영 경로에서 실행될 수 없다. */
        BASELINE
    }

    /**
     * 전략이 볼 정책 변종.
     *
     * <p>전력·온도를 <b>점수에서</b> 뺀 것과 <b>제약에서까지</b> 뺀 것은 다른 판이다. 전자는
     * "가중치를 안 봐도 제약이 막아 주는가", 후자는 "제약까지 없으면 얼마나 나빠지는가"를 묻는다.
     * 두 변종을 섞으면 어느 쪽 효과인지 분리할 수 없어 이름으로 갈라 둔다.</p>
     */
    public enum PolicyVariant {
        /** 판이 준 정책 그대로. */
        BASE,
        /** 전력·온도·네트워크 가중치 0, 다만 전력·온도 <b>제약은 유지</b>. */
        RESOURCE_ONLY_GATED,
        /** 가중치 0 에 전력·온도 <b>제약까지 해제</b>. */
        RESOURCE_ONLY_UNGATED
    }

    /**
     * 필수 항목을 생성 시점에 막는다.
     *
     * <p>{@code placer} 가 {@code null} 이면 배치를 돌릴 때가 되어서야 NPE 가 나고, 그때는 전략
     * 목록을 만든 자리에서 한참 떨어져 있다. 표의 한 열이 조용히 비는 것보다 만들 때 터지는 게 낫다.</p>
     */
    public PlacementStrategy {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("전략 id 가 비었다 — 응답의 열 이름이 된다");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("전략 label 이 비었다: " + id);
        }
        if (kind == null) throw new IllegalArgumentException("전략 kind 가 없다: " + id);
        if (policy == null) throw new IllegalArgumentException("전략 policy 변종이 없다: " + id);
        if (placer == null) throw new IllegalArgumentException("전략 placer 가 없다: " + id);
    }

    /**
     * 운영 엔진을 감싼다 — 메서드 참조 한 줄. 레지스트리는 이 record 를 수집할 수 없다.
     *
     * <p>워크로드 순서를 {@code null}(도착 순서)로 두는 이유: 운영 경로의 배치 순서는 스케줄이 고르는
     * 값이라 엔진 자신의 성질이 아니다. 순서를 바꿔 보고 싶으면 하네스가 이 record 를 다시 만들면 된다.</p>
     */
    public static PlacementStrategy ofEngine(SchedulingEngine e) {
        return new PlacementStrategy(e.getId(), e.getName(), Kind.ENGINE, PolicyVariant.BASE,
                null, e::schedule);
    }

    /** {@code workloadOrder} 가 null 이면 표본 순서(도착 순서)를 쓴다. */
    public boolean usesArrivalOrder() {
        return workloadOrder == null;
    }
}
