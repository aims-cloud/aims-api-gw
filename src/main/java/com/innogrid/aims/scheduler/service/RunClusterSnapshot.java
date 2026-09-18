package com.innogrid.aims.scheduler.service;

import org.springframework.stereotype.Component;

import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;

/**
 * 스케줄 실행 1회의 <b>클러스터 상태</b>를 재는 자리.
 *
 * <p>실행이 이미 만들어 둔 배치 맥락만 읽는다 — 메트릭 저장소를 다시 부르지 않는다. 실행 한 번에 노드 목록을
 * 몇 번 훑을 뿐이라 질의도 트랜잭션도 늘지 않는다.</p>
 *
 * <p><b>예약에 반응하는 축과 그렇지 않은 축을 갈라 둔다.</b> 실측 전력 합과 계측 노드 수는 노드의 실측값에서
 * 오고, 실측값은 배치를 얹어도 변하지 않는다 — 배치 직후에 다시 재지 않기 때문이다. 그래서 그 둘은 전·후를
 * 따로 두지 않고 한 번만 잰다. 반대로 가동 노드 수·회수 가능 전력·평균 온도는 <b>실효</b>값을 보므로 예약이
 * 쌓이면 움직인다 — 이 셋을 전·후로 잰다. 회수 가능 전력은 노드별 값이 실측에서 오지만 <b>어느 노드를
 * 더하는지</b>가 가동 판정으로 갈려, 예약이 노드를 깨우면 그만큼 빠진다.</p>
 *
 * <p>스프링 빈으로 두는 이유는 실행 서비스가 이 계산을 <b>갈아끼울 수 있어야</b> 하기 때문이다 — 계산이
 * 터져도 실행이 깨지지 않는다는 것을 시험으로 보이려면 던지는 대역이 필요하다.</p>
 */
@Component
public class RunClusterSnapshot {

    private final PowerModel powerModel;
    private final ThermalModel thermalModel;

    public RunClusterSnapshot(PowerModel powerModel, ThermalModel thermalModel) {
        this.powerModel = powerModel;
        this.thermalModel = thermalModel;
    }

    /**
     * 예약에 반응하지 않는 축 — 실행 1회에 한 번만 잰다.
     *
     * @param nodeCount     배치 후보에 오른 노드 수
     * @param excludedCount 메트릭을 못 얻어 후보에서 빠진 노드 수(제약 제외가 아니다)
     * @param measuredNodes 전력을 실측한 노드 수. 0 이면 전력 축 전체가 근거 없음이라는 뜻이다
     * @param measuredPowerWatts 실측 전력 합(W). 계측 노드가 없으면 null
     */
    public record Fixed(int nodeCount, int excludedCount, int measuredNodes, Double measuredPowerWatts) {}

    /**
     * 예약에 반응하는 축 — 실행 전과 후에 각각 잰다.
     *
     * @param activeNodes           가동 중으로 세는 노드 수(실효 CPU 기준)
     * @param reclaimableWatts      비어 있는 노드의 유휴 전력 합(W). 계측 노드가 없으면 null.
     *                              계측 노드는 실측에서 역산하고 미계측 노드는 정격으로 어림하므로,
     *                              둘이 섞인 판에서는 실측과 추정이 함께 더해진 값이다
     * @param avgTemperatureCelsius 계측 노드의 평균 실효 온도(℃). 온도를 잰 노드가 없으면 null
     */
    public record Moving(int activeNodes, Double reclaimableWatts, Double avgTemperatureCelsius) {}

    /** 실행 1회에 한 번 재는 축. 가동 문턱을 받지 않는다 — 이 네 값은 문턱과 무관하다. */
    public Fixed fixedOf(PlacementContext ctx) {
        int measured = measuredNodes(ctx);
        // 합계도 계측 술어로 거른다. 후보 전체를 더하면 정격을 모르는 노드의 전력이 분자에만 들어가,
        // 옆 칸의 계측 노드 수로 나눈 대당 전력이 실제와 어긋난다.
        double measuredWatts = ctx.nodes().stream()
                .filter(RunClusterSnapshot::hasPower)
                .mapToDouble(NodeResource::getPowerWatts)
                .sum();
        return new Fixed(
                ctx.nodes().size(),
                ctx.excludedNodes().size(),
                measured,
                measured == 0 ? null : round(measuredWatts));
    }

    /** 전·후로 재는 축. 같은 맥락을 실행 전후에 두 번 넘긴다. */
    public Moving movingOf(PlacementContext ctx, double activeCpuPercent) {
        boolean anyTemperature = ctx.nodes().stream().anyMatch(n -> n.getTemperatureCelsius() > 0);
        return new Moving(
                ctx.activeNodeCount(activeCpuPercent),
                measuredNodes(ctx) == 0 ? null : round(ctx.reclaimableIdleWatts(activeCpuPercent, powerModel)),
                anyTemperature ? round(ctx.averageTemperature(thermalModel)) : null);
    }

    private static int measuredNodes(PlacementContext ctx) {
        return (int) ctx.nodes().stream().filter(RunClusterSnapshot::hasPower).count();
    }

    /**
     * 전력을 실측한 노드인가 — 제약 C6·전력 요약과 <b>같은 술어</b>를 쓴다.
     *
     * <p>정격을 함께 보는 이유는 정격이 없으면 유휴 전력도 회수 가능 전력도 대체값이 되어, 실측이라고
     * 부를 수 있는 것이 사실상 없기 때문이다.</p>
     */
    private static boolean hasPower(NodeResource node) {
        return node.getPowerWatts() > 0 && node.getPowerCapacityWatts() > 0;
    }

    /** 전력·온도 표기 자릿수 — 진단 응답과 같은 두 자리. */
    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
