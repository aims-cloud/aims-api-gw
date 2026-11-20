package com.innogrid.aims.scheduler.engine;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.model.NodeResource;

/**
 * 노드의 전력·온도를 쟀는지 가르는 판정 — 엔진마다 식이 다르고, 그 차이는 현재 동작이라 하나로 합치지 않는다.
 *
 * <ul>
 *   <li>가중치 엔진: 정책이 반영을 껐거나 전력이 0 이하면 중립. 정격은 보지 않는다(정격 0 노드는 전력 모델이 여유 100을 준다).</li>
 *   <li>전력 엔진: 전력과 정격이 모두 0보다 커야 계측.</li>
 *   <li>온도: 실측 온도가 0보다 커야 계측(실효 온도가 아니라 실측 — 계측 여부는 실측의 성질).</li>
 * </ul>
 *
 * <p>중립 조건은 지금 쓰는 부등호 방향 그대로 둔다 — {@code W <= 0}의 부정은 {@code W > 0}이 아니다(NaN).</p>
 */
final class Measured {

    private Measured() {}

    /** 가중치 엔진의 전력 중립 조건. */
    static boolean weightedPowerNeutral(SchedulingPolicy policy, NodeResource node) {
        return !policy.isPowerAwareEnabled() || node.getPowerWatts() <= 0;
    }

    /** 가중치 엔진의 온도 중립 조건. */
    static boolean weightedThermalNeutral(SchedulingPolicy policy, NodeResource node) {
        return !policy.isTemperatureAwareEnabled() || node.getTemperatureCelsius() <= 0;
    }

    /** 전력 엔진의 전력 계측 판정. */
    static boolean powerEnginePower(NodeResource node) {
        return node.getPowerWatts() > 0 && node.getPowerCapacityWatts() > 0;
    }

    /** 온도 계측 판정 — 전력 엔진과 온도 엔진이 같이 쓴다. */
    static boolean thermal(NodeResource node) {
        return node.getTemperatureCelsius() > 0;
    }

    /**
     * 계측된 값만 반올림해 담고, 아니면 {@code null}.
     * 삼항 연산자를 그대로 쓰면 자동 언박싱 때문에 NPE가 날 수 있어 메서드로 감싼다.
     */
    static Double valueOrNull(boolean present, double value) {
        return present ? Double.valueOf(Scores.round(value)) : null;
    }
}
