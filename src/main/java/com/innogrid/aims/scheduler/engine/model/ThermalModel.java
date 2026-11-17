package com.innogrid.aims.scheduler.engine.model;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.NodeResource.ThermalState;

/**
 * 온도 모델 — 온도 제어 기반 자원 할당의 판단 근거를 계산한다.
 *
 * 기존 TemperatureAwareScheduler의 수식(열적 여유, 열적 균형, 냉각 비용, 열관성)을
 * 그대로 옮기고, 경고/임계 2단 상태와 쿨다운을 추가했다.
 *
 * <p>쿨다운 상태는 인메모리에 둔다. 단일 인스턴스 운영을 전제하며 재기동 시 초기화된다.</p>
 *
 * <p>냉각 대기는 (노드, 그 대기를 건 온도 상한) 단위로 든다. 상한 t의 대기는 「실측 온도가 t를 넘었다」는 관측이라
 * 상한이 t 이하인 평가만 막는다 — 상한이 더 높은 평가에는 넘었다는 근거가 없다. 정책 id가 아니라 상한 값을 쓰는 것은
 * 스케줄이 상한을 id 없는 정책 사본에 싣기 때문이다. 같은 상한의 대기가 이미 있으면 만료됐더라도 덮지 않는다(만료 삭제는
 * 확인이 맡는다). 시계는 맵 갱신(compute) 밖에서 읽는다 — 갱신 함수 안에서 같은 맵을 다시 건드리지 않기 위해서다.</p>
 */
@Component
public class ThermalModel {

    private final SchedulerProperties.Thermal props;
    private final Clock clock;

    /** 노드별 · 그 대기를 건 온도 상한별 임계 온도 도달 시각. 안쪽 맵은 불변이고 통째로 바꾼다. */
    private final Map<String, Map<Double, Instant>> criticalSince = new ConcurrentHashMap<>();

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
     * 열적 균형 점수 (0~1). 클러스터 평균보다 낮으면 가점, 높으면 감점하는 대칭식이다 — 핫스팟을 피하는 것이
     * 목표이며, 실제로 피했는지는 실측 온도 관측 지표가 있어야 말할 수 있다. 지금은 비교 API의 생성식 온도
     * 지표(경고 구간 노드 수·온도 표준편차, 보조)만 있다.
     */
    public double balanceScore(double temperature, double averageTemperature) {
        if (averageTemperature <= 0) return 0.5;
        double score = 0.5 + 0.5 * (averageTemperature - temperature) / averageTemperature;
        return Math.max(0, Math.min(1, score));
    }

    /**
     * 워크로드 배치 후 예상 온도(℃).
     * ΔT = k × (요구 CPU / 전체 CPU) × 기준 온도
     *
     * <p>기준은 실측이 아니라 <b>예약분까지 반영한 실효 온도</b>다. 한 실행에서 여러 워크로드를
     * 배치할 때 실측만 보면 상승분이 누적되지 않아, 몇 개를 얹든 "하나 얹은 값"이 나온다.</p>
     *
     * <p>지금 평가 중인 워크로드는 아직 예약되지 않았으므로 이중으로 더해지지 않는다.</p>
     */
    public double predictedTemperature(NodeResource node, int requiredCpu) {
        double base = effectiveTemperature(node);
        double rise = props.getInertiaK()
                * ((double) requiredCpu / Math.max(node.getTotalCpu(), 1))
                * base;
        return base + rise;
    }

    /**
     * 예약분까지 반영한 온도(℃).
     *
     * <p>{@link #predictedTemperature}와 같은 열관성 식을 예약분에 적용한다. 한 실행에서
     * 여러 워크로드를 배치할 때 실측 온도만 보면 상승분이 누적되지 않아, 배치 후 예상 온도
     * 검사가 <b>워크로드 하나만 얹은 값</b>에 머문다.</p>
     *
     * <p>온도를 수집하지 못한 노드(0℃)는 0을 유지한다. 없는 값에 상승분을 얹으면 근거 없는
     * 숫자가 된다.</p>
     */
    public double effectiveTemperature(NodeResource node) {
        double current = node.getTemperatureCelsius();
        if (current <= 0) return current;
        return current * (1.0 + props.getInertiaK() * node.reservedCpuRatio());
    }

    /**
     * 온도 상태를 판정한다.
     * 온도를 수집하지 못한 노드(0℃)는 판단 근거가 없으므로 NORMAL로 본다.
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
     * 경고 구간에서의 감점 비율 (0~1). NORMAL은 0, 임계 직전은 1에 가까워진다.
     */
    public double warningPenalty(double temperature, double warningTemperature, double maxTemperature) {
        if (maxTemperature <= warningTemperature) return 0;
        if (temperature < warningTemperature) return 0;
        double ratio = (temperature - warningTemperature) / (maxTemperature - warningTemperature);
        return Math.max(0, Math.min(1, ratio));
    }

    /** 노드가 그 평가의 온도 상한을 넘었음을 기록한다. 같은 상한의 대기가 이미 있으면 그대로 둔다. */
    public void markCritical(String nodeId, double limitCelsius) {
        Instant now = clock.instant();
        criticalSince.compute(nodeId, (id, marks) -> {
            if (marks != null && marks.containsKey(limitCelsius)) return marks;
            Map<Double, Instant> next = marks == null ? new HashMap<>() : new HashMap<>(marks);
            next.put(limitCelsius, now);
            return Map.copyOf(next);
        });
    }

    /**
     * 지금 맵에 있는 냉각 대기 기록 그대로 — 노드별 · 상한별 시작 시각. 실행 입력 저장이 읽는다.
     * 읽기만 한다: {@link #isCoolingDown}과 달리 만료된 기록을 지우지 않으므로, 읽은 뒤의 판정이 읽기 전과 같다.
     */
    public Map<String, Map<Double, Instant>> cooldownMarks() {
        return Map.copyOf(criticalSince);
    }

    /** 임계 상태에서 회복된 노드의 기록을 모두 지운다. */
    public void clearCritical(String nodeId) {
        criticalSince.remove(nodeId);
    }

    /**
     * 운영자가 노드의 냉각 대기를 푼다 — 그 노드의 기록을 모든 상한에서 한 번에 지우고, 지우기 전에 살아 있던 대기의
     * 상한 → 남은 분을 상한 오름차순으로 돌려준다. 만료된 기록도 함께 지우지만 결과에는 넣지 않으므로, 살아 있던 대기가
     * 없으면 빈 맵이다. 그 노드의 항목을 맵에서 한 번에 떼어 내므로 해제 뒤에 새로 걸린 대기는 남는다.
     */
    public SortedMap<Double, Long> release(String nodeId) {
        Instant now = clock.instant();
        long cooldown = props.getCooldownMinutes();
        SortedMap<Double, Long> released = new TreeMap<>();
        Map<Double, Instant> marks = criticalSince.remove(nodeId);
        if (marks == null) return released;
        marks.forEach((limit, since) -> {
            long left = cooldown - Duration.between(since, now).toMinutes();
            if (left > 0) released.put(limit, left);
        });
        return released;
    }

    /**
     * 상한이 {@code limitCelsius}인 평가에서 쿨다운 중인지 — 상한이 그 값 이상인 살아 있는 대기가 있으면 참.
     * 온도가 내려가도 설정 시간 동안은 배치를 보류해 진동(hysteresis)을 막는다.
     */
    public boolean isCoolingDown(String nodeId, double limitCelsius) {
        return remaining(nodeId, limitCelsius) > 0;
    }

    /** 어느 온도 상한으로든 냉각 대기가 걸린 노드인지(목록용 — 배치 판정은 상한별). */
    public boolean isCoolingDown(String nodeId) {
        return isCoolingDown(nodeId, Double.NEGATIVE_INFINITY);
    }

    /**
     * 냉각 대기 중인 노드가 하나라도 있는지. 기록 전부를 {@link #isCoolingDown}으로 확인하므로
     * 설정 시간이 지난 기록은 이때 모두 지워진다. 중간에 멈추지 않고 끝까지 돈다 — 멈추면 어느
     * 만료 기록이 지워지는지가 맵의 순회 순서에 좌우된다.
     */
    public boolean anyCoolingDown() {
        boolean any = false;
        for (String nodeId : criticalSince.keySet()) {
            if (isCoolingDown(nodeId)) any = true;
        }
        return any;
    }

    /** 상한이 {@code limitCelsius}인 평가를 막는 대기 가운데 가장 늦게 풀리는 것까지 남은 시간(분). 제외 사유 메시지에 쓴다. */
    public long remainingCooldownMinutes(String nodeId, double limitCelsius) {
        return remaining(nodeId, limitCelsius);
    }

    /** 그 노드의 냉각 대기가 모두 풀릴 때까지 남은 시간(분). 목록에 쓴다. */
    public long remainingCooldownMinutes(String nodeId) {
        return remainingCooldownMinutes(nodeId, Double.NEGATIVE_INFINITY);
    }

    /**
     * 만료된 대기를 지운 뒤, 상한이 {@code limit} 이상인 대기의 남은 분 최댓값. 만료 정리는 갱신 함수 안에서 그 순간의 값만
     * 보고 거르므로, 확인 도중 새로 걸린 대기는 지워지지 않는다.
     */
    private long remaining(String nodeId, double limit) {
        Instant now = clock.instant();
        long cooldown = props.getCooldownMinutes();
        Map<Double, Instant> live = criticalSince.computeIfPresent(nodeId, (id, marks) -> unexpired(marks, now, cooldown));
        if (live == null) return 0;
        return live.entrySet().stream()
                .filter(e -> e.getKey() >= limit)
                .mapToLong(e -> cooldown - Duration.between(e.getValue(), now).toMinutes())
                .max().orElse(0);
    }

    /** 만료되지 않은 대기만 남긴 맵. 남는 대기가 없으면 null(키 삭제), 지울 것이 없으면 같은 맵. */
    private static Map<Double, Instant> unexpired(Map<Double, Instant> marks, Instant now, long cooldown) {
        Map<Double, Instant> kept = new HashMap<>();
        marks.forEach((limit, since) -> {
            if (Duration.between(since, now).toMinutes() < cooldown) kept.put(limit, since);
        });
        if (kept.isEmpty()) return null;
        return kept.size() == marks.size() ? marks : Map.copyOf(kept);
    }
}
