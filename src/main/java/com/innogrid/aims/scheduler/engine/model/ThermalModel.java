package com.innogrid.aims.scheduler.engine.model;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.NodeResource.ThermalState;

/**
 * 온도 모델 — 온도 제어 기반 자원 할당의 판단 근거를 계산한다.
 *
 * 기존 TemperatureAwareScheduler 의 수식(열적 여유, 열적 균형, 냉각 비용, 열관성)을
 * 그대로 옮기고, 경고/임계 2단 상태와 쿨다운을 추가했다.
 *
 * <p>쿨다운 상태는 인메모리에 둔다. 단일 인스턴스 운영을 전제하며 재기동 시 초기화된다.</p>
 */
@Component
public class ThermalModel {

    private final SchedulerProperties.Thermal props;
    private final Clock clock;

    /** 노드별 임계 온도 도달 시각. */
    private final Map<String, Instant> criticalSince = new ConcurrentHashMap<>();

    @Autowired
    public ThermalModel(SchedulerProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** 테스트에서 시간을 고정하기 위한 생성자. */
    public ThermalModel(SchedulerProperties properties, Clock clock) {
        this.props = properties.getThermal();
        this.clock = clock;
    }

    /**
     * 열적 여유 점수 (0~1). 임계 온도까지 남은 폭이 클수록 높다.
     */
    public double headroomScore(double temperature, double maxTemperature) {
        if (maxTemperature <= 0) return 0.5;
        return Math.max(0, (maxTemperature - temperature) / maxTemperature);
    }

    /**
     * 냉각 비용 점수 (0~1). 냉각 비용은 온도의 제곱에 비례한다고 본다.
     */
    public double coolingScore(double temperature, double maxTemperature) {
        if (maxTemperature <= 0) return 0.5;
        return 1.0 - Math.pow(temperature / maxTemperature, 2);
    }

    /**
     * 열적 균형 점수 (0~1). 클러스터 평균보다 시원한 노드를 선호해 핫스팟을 막는다.
     */
    public double balanceScore(double temperature, double averageTemperature) {
        if (averageTemperature <= 0) return 0.5;
        double score = 0.5 + 0.5 * (averageTemperature - temperature) / averageTemperature;
        return Math.max(0, Math.min(1, score));
    }

    /**
     * 워크로드 배치 후 예상 온도(℃).
     * ΔT = k × (요구 CPU / 전체 CPU) × 현재 온도
     */
    public double predictedTemperature(NodeResource node, int requiredCpu) {
        double rise = props.getInertiaK()
                * ((double) requiredCpu / Math.max(node.getTotalCpu(), 1))
                * node.getTemperatureCelsius();
        return node.getTemperatureCelsius() + rise;
    }

    /**
     * 예약분까지 반영한 온도(℃).
     *
     * <p>{@link #predictedTemperature} 와 같은 열관성 식을 예약분에 적용한다. 한 실행에서
     * 여러 워크로드를 배치할 때 실측 온도만 보면 상승분이 누적되지 않아, 배치 후 예상 온도
     * 검사가 <b>워크로드 하나만 얹은 값</b>에 머문다.</p>
     *
     * <p>온도를 수집하지 못한 노드(0℃)는 0 을 유지한다. 없는 값에 상승분을 얹으면 근거 없는
     * 숫자가 된다.</p>
     */
    public double effectiveTemperature(NodeResource node) {
        double current = node.getTemperatureCelsius();
        if (current <= 0) return current;
        return current * (1.0 + props.getInertiaK() * node.reservedCpuRatio());
    }

    /**
     * 온도 상태를 판정한다.
     * 온도를 수집하지 못한 노드(0℃)는 판단 근거가 없으므로 NORMAL 로 본다.
     */
    public ThermalState state(double temperature, double warningTemperature, double maxTemperature) {
        if (maxTemperature > 0 && temperature >= maxTemperature) return ThermalState.CRITICAL;
        if (warningTemperature > 0 && temperature >= warningTemperature) return ThermalState.WARNING;
        return ThermalState.NORMAL;
    }

    /** 정책에 경고 온도가 없을 때 쓸 기본값. */
    public double defaultWarningCelsius() {
        return props.getDefaultWarningCelsius();
    }

    /**
     * 경고 구간에서의 감점 비율 (0~1). NORMAL 은 0, 임계 직전은 1 에 가까워진다.
     */
    public double warningPenalty(double temperature, double warningTemperature, double maxTemperature) {
        if (maxTemperature <= warningTemperature) return 0;
        if (temperature < warningTemperature) return 0;
        double ratio = (temperature - warningTemperature) / (maxTemperature - warningTemperature);
        return Math.max(0, Math.min(1, ratio));
    }

    /** 노드가 임계 온도에 도달했음을 기록한다. */
    public void markCritical(String nodeId) {
        criticalSince.putIfAbsent(nodeId, clock.instant());
    }

    /** 임계 상태에서 회복된 노드의 기록을 지운다. */
    public void clearCritical(String nodeId) {
        criticalSince.remove(nodeId);
    }

    /**
     * 쿨다운 중인지 여부.
     * 온도가 내려가도 설정 시간 동안은 배치를 보류해 진동(hysteresis)을 막는다.
     */
    public boolean isCoolingDown(String nodeId) {
        Instant since = criticalSince.get(nodeId);
        if (since == null) return false;

        Duration elapsed = Duration.between(since, clock.instant());
        if (elapsed.toMinutes() >= props.getCooldownMinutes()) {
            criticalSince.remove(nodeId);
            return false;
        }
        return true;
    }

    /** 쿨다운 잔여 시간(분). 제외 사유 메시지에 쓴다. */
    public long remainingCooldownMinutes(String nodeId) {
        Instant since = criticalSince.get(nodeId);
        if (since == null) return 0;
        long elapsed = Duration.between(since, clock.instant()).toMinutes();
        return Math.max(0, props.getCooldownMinutes() - elapsed);
    }
}
