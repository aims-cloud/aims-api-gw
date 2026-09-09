package com.innogrid.aims.scheduler.benchmark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.innogrid.aims.scheduler.metrics.SyntheticCluster;

/**
 * 생성식 오라클 — 합성 클러스터 <b>생성식</b>으로 전력·온도를 계산하는 평가자 전용 계산기.
 * 클래스 이름의 "지상진실"은 <b>엔진 모델의 반대편</b>이라는 뜻이지 실측이라는 뜻이 아니다 —
 * 생성식의 계수는 실데이터 없이 정한 가정이다.
 *
 * <p><b>왜 전력 모델을 쓰지 않는가 — 순환 논증이기 때문이다.</b> 배치 비교의 주 지표로 엔진의
 * 한계 전력({@code PowerModel.realMarginalPower})을 쓰면, 그것은 <b>우리 전력 엔진 총점의 한 항을
 * 그대로 합한 값</b>이다(가중치 0.35 × {@code marginalScore}, {@code marginalScore = 1 −
 * realMarginalPower / pMax}). 그 지표에서 우리 전략이 1등인 것은 발견이 아니라 <b>정의</b>다.
 * 목적 함수를 그대로 평가 함수로 쓰면 어떤 전략을 넣어도 그 목적 함수를 최대화한 전략이 이긴다.</p>
 *
 * <p><b>그래서 평가자만 다른 식을 본다.</b> 합성 클러스터 파일에는 노드별 <b>유휴 전력</b>
 * ({@code idleWatts})이 선언되어 있다. 엔진은 그 값을 모른다 — 엔진의 유휴 전력은 {@code 정격 × idleRatio}
 * (기본 0.3)라는 <b>가정</b>이고, 파일 선언값과 얼마나 벌어져 있는지는 {@link #idleAssumptionGaps(double)} 가
 * 노드별로 돌려준다. 이 클래스는 그 선언값으로만 계산하고, <b>엔진에게는 아무것도 주지 않는다.</b>
 * 평가 기준이 엔진의 입력이 아니라는 것은 두 계산이 <b>독립</b>이라는 뜻일 뿐, 결과가 실물에서
 * 성립한다는 뜻은 아니다 — 생성식도 우리가 정한 가정이다.</p>
 *
 * <p>계산식은 클러스터 파일 머리말에 적힌 생성식 그대로다.</p>
 * <ul>
 *   <li>전력 = {@code idleWatts + (capacityWatts − idleWatts) × (CPU사용률/100)^β} — β 는 판 파일의
 *       {@code powerExponent}(기본 1 — 선형)</li>
 *   <li>온도 = {@code baseTemperature + 0.3 × CPU사용률}</li>
 * </ul>
 *
 * <p><b>전원 제어 두 판의 의미.</b> 배치가 전력을 얼마나 늘리는지는 <b>전원 상태 가정에 따라 답이
 * 다르다.</b> 그래서 두 판을 따로 계산하고, 결과는 반드시 판 이름과 함께 인용한다.</p>
 * <ul>
 *   <li>{@link PowerControl#AVAILABLE} — <b>가정</b>이다. 쓰지 않는 노드는 꺼져 있다고 보고, 빈 노드에
 *       처음 얹으면 그 노드의 유휴 전력이 통째로 새로 든다. 통합(consolidation)의 이득이 이 판에서만 보인다.</li>
 *   <li>{@link PowerControl#UNAVAILABLE} — <b>현 시스템의 사실</b>이다. 이 시스템에는 노드 전원을 끄는
 *       수단도, 이미 돌고 있는 워크로드를 옮기는 수단도 없다. 유휴 노드도 계속 {@code idleWatts} 를 먹으므로
 *       배치의 증분은 <b>동적분뿐</b>이고, 통합으로 아낄 수 있는 전력은 <b>0</b>이다. 전원 제어가 없는데도
 *       통합 이득을 주장하면 그것은 측정이 아니라 희망이다.</li>
 * </ul>
 *
 * <p><b>한계.</b> 이 오라클도 실측이 아니다 — 합성 클러스터의 생성식을 기준으로 삼으므로, 확인하는 것은
 * "엔진의 모델 가정과 생성식이 얼마나 다른가"이고 "생성식이 실제 서버와 같은가"는 확인하지 못한다
 * ({@link BenchmarkRules#LIMITATION} 과 같은 취지). 값은 반올림하지 않는다 — 반올림은 DTO 계층의 일이다.</p>
 *
 * <p>이 클래스는 스프링 빈이 아닌 <b>내부 계산기</b>다. 호출자는 항상 자기 클러스터의 노드만 넘기므로,
 * 모르는 노드나 어긋난 인자는 사용자 입력 오류가 아니라 <b>하네스의 결함</b>이다 — 조용히 0 을 돌려주지 않고
 * {@link IllegalStateException} 으로 크게 실패한다.</p>
 */
public final class GroundTruthOracle {

    /** 전원 상태 가정. 이 시스템에는 노드 전원을 끄는 수단이 없다 — {@code UNAVAILABLE} 이 현 사실이다. */
    public enum PowerControl { AVAILABLE, UNAVAILABLE }

    /** 온도 생성식의 CPU 기여 계수 — 클러스터 파일 머리말의 0.3(100% 이면 +30℃). */
    private static final double TEMPERATURE_CPU_COEFFICIENT = 0.3;

    private final Map<String, SyntheticCluster.Node> nodesById;
    private final List<String> sortedNodeIds;
    private final double powerExponent;

    public GroundTruthOracle(SyntheticCluster cluster) {
        if (cluster == null) {
            throw new IllegalStateException("지상진실 오라클에 클러스터가 없습니다");
        }
        this.powerExponent = cluster.powerExponent();
        if (!(powerExponent > 0) || !Double.isFinite(powerExponent)) {
            throw new IllegalStateException("생성식 지수는 0 보다 큰 유한한 값이어야 합니다: " + powerExponent);
        }
        Map<String, SyntheticCluster.Node> byId = new LinkedHashMap<>();
        for (SyntheticCluster.Node node : cluster.nodes()) {
            if (node.capacityWatts() <= 0) {
                throw new IllegalStateException("정격 전력이 0 이하인 노드로는 지상진실을 계산할 수 없습니다: " + node.nodeId());
            }
            if (node.idleWatts() < 0 || node.idleWatts() > node.capacityWatts()) {
                throw new IllegalStateException("유휴 전력이 0~정격 밖입니다: " + node.nodeId()
                        + " (유휴 " + node.idleWatts() + "W, 정격 " + node.capacityWatts() + "W)");
            }
            byId.put(node.nodeId(), node);
        }
        this.nodesById = Map.copyOf(byId);
        this.sortedNodeIds = byId.keySet().stream().sorted().toList();
    }

    // ── 노드 단위 물리량 ──────────────────────────────────────

    /**
     * 노드 한 대의 생성식 기준 소비 전력(W). {@code cpuPercent} 는 0~100. 실측이 아니다.
     *
     * <p>생성식 그대로: {@code idleWatts + (capacityWatts − idleWatts) × (cpuPercent/100)^β}. 지수 β 는
     * 판 파일이 선언하고(기본 1 — 선형), 여기서는 분기 없이 {@link Math#pow} 를 쓴다 —
     * {@code Math.pow(x, 1.0) == x} 이고 이 식은 이미 {@code a × (b/100)} 형이라 β=1 에서 비트까지 같다.
     * 사용률은 물리적으로 0~100 을 벗어날 수 없으므로 범위를 벗어난 값은 잘라 쓴다 —
     * 정격을 넘는 전력을 만들어내지 않는다.</p>
     */
    public double nodePowerWatts(String nodeId, double cpuPercent) {
        SyntheticCluster.Node node = node(nodeId);
        return node.idleWatts()
                + (node.capacityWatts() - node.idleWatts()) * Math.pow(utilization(cpuPercent), powerExponent);
    }

    /**
     * 노드 한 대의 생성식 기준 온도(℃). 실측이 아니다.
     *
     * <p>생성식 그대로: {@code baseTemperature + 0.3 × cpuPercent}. 전력과 마찬가지로 <b>계측 여부를 보지 않는다</b> —
     * 이 클래스에는 {@code ipmi} 를 읽는 자리가 한 곳도 없다. {@code ipmi: false} 인 노드도 물리적으로는
     * 뜨거워지고, 엔진이 그것을 못 볼 뿐이다. 미계측 노드를
     * 지표에서 뺄지는 판정 규칙({@link BenchmarkRules#WARN_ZONE_NODES})의 일이고, 오라클의 일이 아니다.</p>
     */
    public double nodeTemperatureC(String nodeId, double cpuPercent) {
        SyntheticCluster.Node node = node(nodeId);
        return node.baseTemperature() + TEMPERATURE_CPU_COEFFICIENT * clampPercent(cpuPercent);
    }

    // ── 클러스터 총량 ─────────────────────────────────────────

    /**
     * 클러스터 총전력(W).
     *
     * <p>{@link PowerControl#AVAILABLE} — 사용 노드({@code usedNodeIds})만 통전한다. 나머지는 0.<br>
     * {@link PowerControl#UNAVAILABLE} — 전 노드가 통전된다(유휴 노드도 {@code idleWatts} 를 먹는다).</p>
     *
     * <p>사용률이 없는 노드는 0%(완전 유휴)로 본다 — {@code UNAVAILABLE} 판에서 그 노드는 0W 가 아니라
     * <b>{@code idleWatts}</b> 를 낸다. 이것이 두 판의 차이가 드러나는 자리다.</p>
     */
    public double clusterPowerWatts(Map<String, Double> cpuPercentByNode,
                                    Set<String> usedNodeIds, PowerControl mode) {
        if (cpuPercentByNode == null || usedNodeIds == null || mode == null) {
            throw new IllegalStateException(
                    "클러스터 총전력에는 노드별 사용률·사용 노드 집합·전원 상태 가정이 모두 필요합니다");
        }
        cpuPercentByNode.keySet().forEach(this::node);   // 모르는 노드가 섞였으면 여기서 크게 실패한다
        usedNodeIds.forEach(this::node);

        double total = 0;
        for (String nodeId : sortedNodeIds) {
            if (mode == PowerControl.AVAILABLE && !usedNodeIds.contains(nodeId)) continue;
            Double cpuPercent = cpuPercentByNode.get(nodeId);
            total += nodePowerWatts(nodeId, cpuPercent == null ? 0 : cpuPercent);
        }
        return total;
    }

    // ── 배치 1건의 증분 ───────────────────────────────────────

    /**
     * 생성식 기준 배치 1건의 증분 전력(W) — 동적분 + (전원 제어가 있고 빈 노드였으면) 파일에 선언된 {@code idleWatts}.
     *
     * <p>동적분 = {@code (capacityWatts − idleWatts) × (u₂^β − u₁^β)}, {@code u₁ = cpuBeforePercent/100},
     * {@code u₂ = u₁ + requiredCpu/cores}. <b>지수가 1 이 아니면 같은 코어를 얹어도 증분이 현재 부하에 따라
     * 다르다</b> — 곡선 위 두 점의 차이지 기울기 하나가 아니다. β=1 에서는 두 항이 상쇄해 예전처럼
     * {@code (정격−유휴) × requiredCpu/cores} 가 된다. 두 사용률 모두 0~1 로 자른다 — 물리적으로
     * 그 밖의 값이 없고, 자르지 않으면 정격을 넘는 전력을 만들어낸다.</p>
     *
     * <p>깨우는 비용은 {@code AVAILABLE} 판에서만 든다. {@code UNAVAILABLE} 판에서는 그 노드가 이미 통전되어
     * 유휴 전력을 먹고 있으므로 <b>새로 드는 유휴 전력이 없다</b>. 엔진은 이 자리에서 파일 선언값 대신
     * {@code 정격 × 0.3} 을 쓴다 — 그 격차가 엔진 가정과 생성식의 차이다.</p>
     */
    public double marginalPowerWatts(String nodeId, double cpuBeforePercent, int requiredCpu,
                                     boolean nodeWasIdle, PowerControl mode) {
        if (mode == null) {
            throw new IllegalStateException("증분 전력에는 전원 상태 가정이 필요합니다: " + nodeId);
        }
        SyntheticCluster.Node node = node(nodeId);
        double before = utilization(cpuBeforePercent);
        double after = Math.max(0, Math.min(1.0, before + (double) requiredCpu / node.cores()));
        double dynamic = (node.capacityWatts() - node.idleWatts())
                * (Math.pow(after, powerExponent) - Math.pow(before, powerExponent));
        boolean wakesNode = mode == PowerControl.AVAILABLE && nodeWasIdle;
        return wakesNode ? dynamic + node.idleWatts() : dynamic;
    }

    // ── 엔진 가정과의 격차 ───────────────────────────────────

    /**
     * 엔진 가정({@code 정격 × engineIdleRatio})과 파일 선언값 {@code idleWatts} 의 노드별 격차. 응답의 전제 블록에 그대로 싣는다.
     *
     * <p>이 목록이 <b>엔진의 가정과 생성식이 어디서 갈리는지</b>를 보이는 표다. 엔진이 쓰는 유휴 전력은 정격의
     * 상수배이고, 오라클이 쓰는 유휴 전력은 파일에 선언된 값이다. 두 값이 노드마다 얼마나 다른지 밝혀 두면,
     * 생성식 기준 전력이 엔진 점수의 재탕이 아니라는 것이 표에서 바로 보인다 — 다만 그것은 두 계산이
     * 독립이라는 뜻이고, 어느 쪽이 실물에 가까운지는 이 표가 말하지 않는다.</p>
     *
     * <p>정렬은 {@link #nodeIds()} 와 같다(노드 id 오름차순) — 표의 행 순서가 실행마다 바뀌지 않게.</p>
     */
    public List<IdleGap> idleAssumptionGaps(double engineIdleRatio) {
        if (!(engineIdleRatio >= 0) || engineIdleRatio > 1) {
            throw new IllegalStateException("엔진 유휴 비율은 0~1 이어야 합니다: " + engineIdleRatio);
        }
        List<IdleGap> gaps = new ArrayList<>(sortedNodeIds.size());
        for (String nodeId : sortedNodeIds) {
            SyntheticCluster.Node node = nodesById.get(nodeId);
            gaps.add(new IdleGap(nodeId, node.capacityWatts(), node.believedCapacityWatts(), node.idleWatts(),
                    node.believedCapacityWatts() * engineIdleRatio,
                    node.idleWatts() / node.capacityWatts(),
                    (node.capacityWatts() - node.idleWatts()) / node.cores()));
        }
        return List.copyOf(gaps);
    }

    /**
     * 노드 한 대의 유휴 전력 — 엔진 가정 대 파일 선언값.
     *
     * @param nodeId                 노드 id
     * @param capacityWatts          정격 전력(W) — 생성식이 쓰는 값
     * @param engineBelievedCapacityWatts 판이 엔진에게 알려 준 정격(W). 판이 정격을 어긋나게 주지 않으면
     *                               {@code capacityWatts} 와 같다 — 다르면 엔진의 여유·벌점 계산이 그만큼 기운다
     * @param declaredIdleWatts      파일에 선언된 유휴 전력(W). 엔진은 이 값을 <b>모른다</b>. 실측이 아니다
     * @param engineAssumedIdleWatts 엔진이 가정하는 유휴 전력(W) = <b>엔진이 믿는 정격</b> × idleRatio
     * @param declaredIdleRatio      선언 유휴 비율 = 선언 유휴 / 정격. 엔진의 idleRatio 와 견줄 수 있는 형태
     * @param generatorWattsPerCore  코어당 동적 전력(W/코어) = (정격 − 선언 유휴) / 코어 — 0→100% 구간의
     *                               <b>평균</b>(시컨트) 값이다. 볼록 판(β&gt;1)에서 사용률 u 의 한계 기울기는
     *                               이 값의 {@code β·u^(β−1)} 배라 이 열로 한계 비용을 읽으면 안 된다.
     *                               유휴 전력은 뺀 값이다 — 빈 노드를 깨우는 비용은
     *                               {@link GroundTruthOracle#marginalPowerWatts} 가 따로 센다
     */
    public record IdleGap(String nodeId, double capacityWatts, double engineBelievedCapacityWatts,
                          double declaredIdleWatts, double engineAssumedIdleWatts,
                          double declaredIdleRatio, double generatorWattsPerCore) {

        /** 가정과 선언값의 절대 격차(W). 부호는 노드마다 다르므로 크기 비교에는 이 값을 쓴다. */
        public double absoluteGapWatts() {
            return Math.abs(declaredIdleWatts - engineAssumedIdleWatts);
        }
    }

    // ── 열 상승 모델의 척도 점검 ─────────────────────────────

    /**
     * 노드마다 <b>코어 1개 예약당 온도 상승</b>을 세 식으로 나란히 낸다. 응답의 전제 블록에 싣는다.
     *
     * <p>세 값은 세 <b>가정</b>이고, 어느 것도 실측이 아니다. 이 표는 격차·비율·판정을 내지 않는다 —
     * "엔진이 N배 과소평가한다"는 문장은 생성식이 옳다는 전제가 필요하고, 그 전제의 근거는 코드·문서 어디에도
     * 없다. 게다가 그 배수는 <b>단위 선택의 산물</b>이다: 같은 엔진 식을 ℃ 로 재면 생성식보다 작고 K 로 재면
     * 생성식보다 크다. 그래서 두 번째 열이 있다.</p>
     *
     * <ul>
     *   <li>{@code engineRisePerCoreC} — 엔진 식 {@code ΔT = inertiaK × (요구코어/총코어) × T} 에 ℃ 눈금의
     *       {@code T = baseTemperature} 를 넣은 값. 코어 1개면 {@code inertiaK × base / cores}.</li>
     *   <li>{@code engineRisePerCoreIfKelvin} — <b>같은 식</b>에 같은 온도를 K 눈금으로 넣은 값
     *       ({@code inertiaK × (base + 273.15) / cores}). 물리적으로 같은 상태인데 값이 다르다 —
     *       상승분이 눈금의 원점에 비례하기 때문이다. 온도 차(ΔT)는 원점에 무관해야 하므로 이 식은
     *       절대 척도 위에서 정의되지 않은 모델이고, 이 사실은 생성식과 무관하게 엔진 코드만으로 성립한다.</li>
     *   <li>{@code generatorRisePerCoreC} — 생성식 {@code 온도 = base + 0.3 × CPU%} 에서 코어 1개가 CPU 를
     *       {@code 100/cores}%p 올리므로 {@code 30 / cores}. 온도와 무관한 가산형이다 — 엔진 식과 함수형이 다르다.</li>
     * </ul>
     *
     * <p>기준 온도는 {@code baseTemperature}(CPU 0%) 로 고정한다 — 판 하나에 한 값을 실으려면 시각을 타지
     * 않아야 하고, 세 값 모두 판 파일의 상수만으로 계산된다. 정렬은 {@link #idleAssumptionGaps(double)} 와 같다.</p>
     */
    public List<ThermalRiseScale> thermalRiseScaleCheck(double engineInertiaK) {
        if (!(engineInertiaK >= 0)) {
            throw new IllegalStateException("엔진 열관성 계수는 0 이상이어야 합니다: " + engineInertiaK);
        }
        List<ThermalRiseScale> rows = new ArrayList<>(sortedNodeIds.size());
        for (String nodeId : sortedNodeIds) {
            SyntheticCluster.Node node = nodesById.get(nodeId);
            if (node.cores() <= 0) {
                throw new IllegalStateException("코어가 0 이하인 노드로는 열 상승을 계산할 수 없습니다: " + nodeId);
            }
            double base = node.baseTemperature();
            rows.add(new ThermalRiseScale(nodeId, node.cores(), base,
                    engineInertiaK * base / node.cores(),
                    engineInertiaK * (base + CELSIUS_TO_KELVIN) / node.cores(),
                    TEMPERATURE_CPU_COEFFICIENT * 100.0 / node.cores()));
        }
        return List.copyOf(rows);
    }

    /** 0℃ 의 절대온도. 엔진 식이 눈금 원점에 의존함을 보이는 데만 쓴다. */
    private static final double CELSIUS_TO_KELVIN = 273.15;

    /**
     * 노드 한 대의 코어당 온도 상승 — 세 가정을 나란히. 격차·비율·판정 필드는 두지 않는다.
     *
     * @param nodeId                   노드 id
     * @param cores                    총 코어 — 세 식 모두 코어에 반비례한다
     * @param baseTemperatureC         기준 온도(℃) = CPU 0% 의 생성식 값
     * @param engineRisePerCoreC       엔진 식을 ℃ 눈금으로 = {@code inertiaK × base / cores}
     * @param engineRisePerCoreIfKelvin 같은 엔진 식을 K 눈금으로 = {@code inertiaK × (base + 273.15) / cores}.
     *                                 앞 값과 다르다는 것이 척도 의존의 증거다
     * @param generatorRisePerCoreC    생성식 상승 = {@code 30 / cores}. 온도 무관 가산형
     */
    public record ThermalRiseScale(String nodeId, int cores, double baseTemperatureC,
                                   double engineRisePerCoreC, double engineRisePerCoreIfKelvin,
                                   double generatorRisePerCoreC) {
    }

    /** 노드 id 목록(정렬됨). */
    public List<String> nodeIds() {
        return sortedNodeIds;
    }

    // ── 내부 ─────────────────────────────────────────────────

    private SyntheticCluster.Node node(String nodeId) {
        SyntheticCluster.Node node = nodesById.get(nodeId);
        if (node == null) {
            throw new IllegalStateException("지상진실 오라클이 모르는 노드입니다: " + nodeId
                    + " (클러스터 노드: " + sortedNodeIds + ")");
        }
        return node;
    }

    private static double utilization(double cpuPercent) {
        return clampPercent(cpuPercent) / 100.0;
    }

    private static double clampPercent(double cpuPercent) {
        return Math.max(0, Math.min(100, cpuPercent));
    }
}
