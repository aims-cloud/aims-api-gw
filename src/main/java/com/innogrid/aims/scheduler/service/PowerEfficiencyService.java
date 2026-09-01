package com.innogrid.aims.scheduler.service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PowerEfficiencyResponse;
import com.innogrid.aims.scheduler.dto.PowerEfficiencyTrendResponse;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.model.NodeResource;

/**
 * 서버별 전력 효율성 지표 계산·평가.
 *
 * <p>계산 자체는 {@link PowerModel} 이 이미 갖고 있다. 이 서비스가 더하는 것은
 * <b>클러스터 단위 평가</b>다 — 등급과 순위는 다른 노드와 비교해야 나오므로 노드 하나만
 * 보는 모델 계층에서는 만들 수 없다.</p>
 *
 * <p><b>전력 메트릭이 없는 노드를 걸러내는 것이 이 서비스의 핵심 책임이다.</b>
 * {@link PowerModel#proportionality} 는 전력 용량만 보고 실측 전력이 0 인지는 보지 않아,
 * IPMI 가 없는 노드에도 그럴듯한 값을 돌려준다. 예를 들어 CPU 13%·전력 0W·용량 500W 이면
 * 비례성 0.61 이 나온다. 판단 근거가 없는데 숫자가 보이면 그대로 믿게 되므로,
 * 평가 불가 노드는 파생 지표를 모두 {@code null} 로 비운다.</p>
 */
@Service
public class PowerEfficiencyService {

    private static final Logger log = LoggerFactory.getLogger(PowerEfficiencyService.class);

    /** 4분위 상대 등급을 매기려면 최소 이만큼은 평가 가능해야 한다. */
    static final int MIN_NODES_FOR_GRADE = 4;

    /** 설정이 잘못됐을 때 쓸 PEI 정규화 기준. */
    private static final double FALLBACK_PEI_NORMALIZER = 0.5;

    // ── 권고 임계 ──────────────────────────────────────────────
    private static final double LOW_UTILIZATION_PERCENT = 30.0;
    private static final double LOW_HEADROOM_PERCENT = 20.0;

    /** {@code 30s}·{@code 5m}·{@code 1h}·{@code 1d} 형식. */
    private static final Pattern STEP = Pattern.compile("^(\\d+)([smhd])$");

    private final SchedulingService schedulingService;
    private final PowerModel powerModel;
    private final VictoriaMetricsClient metricsClient;
    private final SchedulerProperties properties;

    public PowerEfficiencyService(SchedulingService schedulingService,
                                  PowerModel powerModel,
                                  VictoriaMetricsClient metricsClient,
                                  SchedulerProperties properties) {
        this.schedulingService = schedulingService;
        this.powerModel = powerModel;
        this.metricsClient = metricsClient;
        this.properties = properties;
    }

    /** 현재 노드 상태를 수집해 평가한다. */
    public PowerEfficiencyResponse evaluateCurrent() {
        return evaluate(schedulingService.getNodeResources());
    }

    /**
     * 주어진 노드 스냅샷을 평가한다.
     *
     * <p>스냅샷을 인자로 받는 형태라 수집 경로 없이도 호출할 수 있다.
     * 실제 전력 데이터를 얻기 전까지는 이 메서드에 합성 노드를 넣어 동작을 확인한다.</p>
     */
    public PowerEfficiencyResponse evaluate(List<NodeResource> nodes) {
        List<NodeResource> snapshot = nodes == null ? List.of() : nodes;

        List<PowerEfficiencyResponse.NodeEfficiency> evaluated = new ArrayList<>();
        for (NodeResource node : snapshot) {
            evaluated.add(measure(node));
        }

        assignRanksAndGrades(evaluated);
        List<PowerEfficiencyResponse.NodeEfficiency> withAdvice =
                evaluated.stream().map(this::withRecommendation).toList();

        return new PowerEfficiencyResponse(OffsetDateTime.now(), summarize(withAdvice), withAdvice);
    }

    // ── 노드별 지표 ─────────────────────────────────────────────

    /**
     * 노드 하나의 지표를 잰다. 등급·순위는 클러스터 전체를 봐야 하므로 뒤에서 채운다.
     */
    private PowerEfficiencyResponse.NodeEfficiency measure(NodeResource node) {
        boolean evaluable = node.getPowerWatts() > 0;

        if (!evaluable) {
            // 전력을 모르면 PEI 도 비례성도 여유율도 의미가 없다. 숫자를 남기지 않는다.
            return new PowerEfficiencyResponse.NodeEfficiency(
                    node.getMdcId(), node.getNodeId(), false,
                    node.getCpuUsagePercent(), node.getPowerWatts(), node.getPowerCapacityWatts(),
                    null, null, null, null, null,
                    null, null, String.valueOf(node.getPowerSource()), null);
        }

        return new PowerEfficiencyResponse.NodeEfficiency(
                node.getMdcId(), node.getNodeId(), true,
                node.getCpuUsagePercent(), node.getPowerWatts(), node.getPowerCapacityWatts(),
                round(powerModel.idlePower(node)),
                round(node.powerHeadroomPercent()),
                round(powerModel.rawPei(node)),
                round(normalizedPei(node)),
                round(powerModel.proportionality(node)),
                null, null, String.valueOf(node.getPowerSource()), null);
    }

    /**
     * 정규화 PEI. 설정이 0 이하로 들어오면 {@code min(1, x/0)} 이 1.0 이 되어
     * 모든 노드가 조용히 만점을 받으므로 기본값으로 되돌린다.
     */
    private double normalizedPei(NodeResource node) {
        double normalizer = properties.getPower().getPeiNormalizer();
        if (normalizer <= 0) {
            log.warn("aims.scheduler.power.pei-normalizer 가 {} 입니다. 기본값 {} 을 씁니다.",
                    normalizer, FALLBACK_PEI_NORMALIZER);
            return Math.min(1.0, powerModel.rawPei(node) / FALLBACK_PEI_NORMALIZER);
        }
        return powerModel.normalizedPei(node);
    }

    // ── 클러스터 평가 ───────────────────────────────────────────

    /**
     * PEI 내림차순 순위와 4분위 등급을 매긴다. 평가 불가 노드는 대상에서 제외한다.
     *
     * <p>동점이면 노드 ID 사전순으로 안정 정렬해 호출할 때마다 순위가 흔들리지 않게 한다.</p>
     */
    private void assignRanksAndGrades(List<PowerEfficiencyResponse.NodeEfficiency> nodes) {
        List<PowerEfficiencyResponse.NodeEfficiency> ranked = nodes.stream()
                .filter(PowerEfficiencyResponse.NodeEfficiency::evaluable)
                .sorted(Comparator
                        .comparingDouble((PowerEfficiencyResponse.NodeEfficiency n) -> n.peiRaw()).reversed()
                        .thenComparing(PowerEfficiencyResponse.NodeEfficiency::nodeId))
                .toList();

        boolean gradable = ranked.size() >= MIN_NODES_FOR_GRADE;
        for (int i = 0; i < ranked.size(); i++) {
            PowerEfficiencyResponse.NodeEfficiency target = ranked.get(i);
            int index = nodes.indexOf(target);
            nodes.set(index, target.ranked(i + 1, gradable ? gradeOf(i, ranked.size()) : null));
        }
    }

    /** 상위 25% 부터 A·B·C·D. 나누어떨어지지 않으면 앞 등급이 한 자리 더 갖는다. */
    private String gradeOf(int zeroBasedRank, int total) {
        int quartile = (int) Math.floor((double) zeroBasedRank * 4 / total);
        return switch (Math.min(quartile, 3)) {
            case 0 -> "A";
            case 1 -> "B";
            case 2 -> "C";
            default -> "D";
        };
    }

    private PowerEfficiencyResponse.Summary summarize(
            List<PowerEfficiencyResponse.NodeEfficiency> nodes) {

        List<PowerEfficiencyResponse.NodeEfficiency> evaluable = nodes.stream()
                .filter(PowerEfficiencyResponse.NodeEfficiency::evaluable).toList();

        Double averagePei = evaluable.isEmpty() ? null
                : round(evaluable.stream().mapToDouble(PowerEfficiencyResponse.NodeEfficiency::peiRaw)
                        .average().orElse(0));

        boolean gradeAvailable = evaluable.size() >= MIN_NODES_FOR_GRADE;
        String reason = gradeAvailable ? null : String.format(
                "평가 가능 노드 %d대로 상대 등급을 산출할 수 없습니다(최소 %d대). "
                        + "전력 메트릭이 있는 노드가 늘어나면 자동으로 매겨집니다.",
                evaluable.size(), MIN_NODES_FOR_GRADE);

        return new PowerEfficiencyResponse.Summary(
                nodes.size(), evaluable.size(), averagePei,
                round(nodes.stream().mapToDouble(PowerEfficiencyResponse.NodeEfficiency::powerWatts).sum()),
                round(nodes.stream().mapToDouble(PowerEfficiencyResponse.NodeEfficiency::powerCapacityWatts).sum()),
                gradeAvailable, reason);
    }

    // ── 권고 ───────────────────────────────────────────────────

    /**
     * 노드 상태에 맞는 권고 한 줄. 해당 사항이 없으면 {@code null}.
     * 여러 조건에 걸리면 더 시급한 것 하나만 남긴다.
     *
     * <p><b>비례성은 권고 근거로 쓰지 않는다.</b> 비례성은 이상 곡선과의 <i>거리</i>라
     * 덜 쓰는 노드도 낮게 나온다. CPU 90% 를 쓰면서 전력은 150W 만 쓰는 아주 효율적인
     * 노드가 비례성 0.37 을 받아 "전력 관리 설정을 확인하라"는 경고를 받는 식이다.</p>
     *
     * <p>그렇다고 "이상보다 많이 쓸 때만" 으로 좁히면 규칙이 아예 발동하지 않는다.
     * 비례성 0.5 미만이면서 과다 사용이라는 것은 {@code |실측−이상| > 0.5×용량} 이고
     * {@code 이상 ≥ 유휴 = 0.3×용량} 이므로 {@code 실측 > 0.8×용량} 을 뜻한다. 그런 노드는
     * 여유율이 20% 미만이라 바로 위의 여유율 규칙에 먼저 걸린다. 도달할 수 없는 분기라
     * 남겨 두지 않았다. 비례성 값 자체는 응답에 그대로 실어 판단 근거로 제공한다.</p>
     */
    private PowerEfficiencyResponse.NodeEfficiency withRecommendation(
            PowerEfficiencyResponse.NodeEfficiency node) {

        if (!node.evaluable()) {
            return node.advised("전력 메트릭이 없어 평가할 수 없습니다. IPMI exporter 연동을 확인하세요");
        }
        if (node.headroomPercent() != null && node.headroomPercent() < LOW_HEADROOM_PERCENT) {
            return node.advised("전력 여유가 부족합니다. 추가 배치를 제한하세요");
        }
        // 등급이 있으면 최하위 구간으로 좁히고, 표본이 모자라 등급을 못 매긴 작은 클러스터에서는
        // 저부하 사실만으로 판단한다. 등급에만 걸어 두면 노드 3대짜리 환경에서 이 권고가
        // 영영 나오지 않는다 — 등급을 못 매긴다고 노드가 놀고 있다는 사실까지 감출 이유는 없다.
        boolean bottomOrUngraded = node.grade() == null || "D".equals(node.grade());
        if (bottomOrUngraded && node.cpuUsagePercent() < LOW_UTILIZATION_PERCENT) {
            return node.advised("저부하로 전력 효율이 낮습니다. 워크로드 통합을 검토하세요");
        }
        return node;
    }

    // ── 추세 ───────────────────────────────────────────────────

    /**
     * 노드 하나의 전력·CPU·PEI 추세.
     *
     * <p>메트릭 저장소에서 그때그때 읽는다. CPU 식은 스냅샷 수집이 쓰는 것과 <b>같은 식</b>을
     * 써야 목록 API 와 값이 어긋나지 않는다.</p>
     *
     * <p>저장소가 응답하지 않아도 예외를 올리지 않는다. IPMI 가 없는 환경에서는 빈 결과가
     * 정상이고, 조회 실패와 데이터 없음을 구분해 사유를 남긴다.</p>
     */
    public PowerEfficiencyTrendResponse trend(String nodeId, OffsetDateTime from,
                                              OffsetDateTime to, String step) {
        OffsetDateTime end = to != null ? to : OffsetDateTime.now();
        OffsetDateTime start = from != null ? from
                : end.minusHours(properties.getPower().getTrendDefaultHours());
        String interval = (step == null || step.isBlank())
                ? properties.getPower().getTrendDefaultStep() : step.trim();

        validateRange(start, end, interval);

        try {
            Map<Long, Double> power = seriesOf(String.format(
                    "ipmi_dcmi_power_consumption_watts{instance=\"%s\"}", nodeId), start, end, interval);
            // 스냅샷 수집과 동일한 식. 다르면 목록 API 와 추세의 값이 맞지 않는다.
            Map<Long, Double> cpu = seriesOf(String.format(
                    "100 - (avg(irate(node_cpu_seconds_total{mode=\"idle\",instance=\"%s\"}[5m])) * 100)",
                    nodeId), start, end, interval);

            if (power.isEmpty() && cpu.isEmpty()) {
                return PowerEfficiencyTrendResponse.empty(nodeId, start, end, interval,
                        "해당 구간에 전력·CPU 메트릭이 없습니다. IPMI exporter 연동을 확인하세요");
            }
            return new PowerEfficiencyTrendResponse(nodeId, start, end, interval,
                    join(power, cpu), partialReason(power, cpu));

        } catch (RuntimeException e) {
            // 조회 실패로 500 을 내면 화면이 통째로 깨진다. 사유만 담아 빈 결과로 돌려준다.
            log.warn("전력 추세 조회 실패: node={}, reason={}", nodeId, e.getMessage());
            return PowerEfficiencyTrendResponse.empty(nodeId, start, end, interval,
                    "메트릭 저장소를 조회하지 못했습니다: " + e.getMessage());
        }
    }

    /**
     * 구간과 간격이 감당할 만한지 본다.
     * 메트릭 클라이언트에 읽기 타임아웃이 없어, 무거운 질의는 애초에 내보내지 않는다.
     */
    private void validateRange(OffsetDateTime start, OffsetDateTime end, String step) {
        if (!start.isBefore(end)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "from 은 to 보다 앞서야 합니다");
        }
        long stepSeconds = parseStepSeconds(step);
        long points = Duration.between(start, end).getSeconds() / stepSeconds;
        int max = properties.getPower().getTrendMaxPoints();
        if (points > max) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, String.format(
                    "조회 지점이 너무 많습니다(%d개, 최대 %d개). 구간을 줄이거나 step 을 늘리세요",
                    points, max));
        }
    }

    /** {@code 30s}·{@code 5m}·{@code 1h}·{@code 1d} 형식을 초로 옮긴다. */
    private long parseStepSeconds(String step) {
        Matcher matcher = STEP.matcher(step);
        if (!matcher.matches()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "step 형식이 올바르지 않습니다: " + step + " (예: 30s, 5m, 1h, 1d)");
        }
        long amount = Long.parseLong(matcher.group(1));
        if (amount <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "step 은 0보다 커야 합니다: " + step);
        }
        return switch (matcher.group(2)) {
            case "s" -> amount;
            case "m" -> amount * 60;
            case "h" -> amount * 3600;
            default -> amount * 86400;
        };
    }

    /**
     * 한쪽 시계열만 들어왔을 때의 사유. 양쪽 다 있으면 {@code null}.
     *
     * <p>배포 환경이 실제로 이 상태다 — CPU 는 수집되는데 IPMI 가 없어 전력만 빈다.
     * 이때 사유를 달지 않으면 화면은 PEI 가 전부 비어 있는 이유를 알 길이 없다.</p>
     */
    private String partialReason(Map<Long, Double> power, Map<Long, Double> cpu) {
        if (power.isEmpty()) {
            return "전력 메트릭이 없어 PEI 를 계산하지 못했습니다. CPU 추이만 제공합니다 — "
                    + "IPMI exporter 연동을 확인하세요";
        }
        if (cpu.isEmpty()) {
            return "CPU 메트릭이 없어 PEI 를 계산하지 못했습니다. 전력 추이만 제공합니다";
        }
        return null;
    }

    /** 시계열 하나를 타임스탬프→값 으로 편다. 여러 시리즈가 오면 첫 건만 쓴다. */
    private Map<Long, Double> seriesOf(String promQL, OffsetDateTime start,
                                       OffsetDateTime end, String step) {
        List<Map<String, Object>> results = metricsClient.queryRangeResults(
                promQL, start.toInstant(), end.toInstant(), step);
        Map<Long, Double> series = new LinkedHashMap<>();
        if (results.isEmpty()) return series;

        Object raw = results.get(0).get("values");
        if (!(raw instanceof List<?> values)) return series;

        for (Object point : values) {
            if (!(point instanceof List<?> pair) || pair.size() < 2) continue;
            try {
                series.put((long) Double.parseDouble(pair.get(0).toString()),
                        Double.parseDouble(pair.get(1).toString()));
            } catch (NumberFormatException ignored) {
                // 저장소가 NaN 같은 값을 보낼 수 있다. 그 지점만 건너뛴다.
            }
        }
        return series;
    }

    /**
     * 전력과 CPU 를 타임스탬프로 맞춘다.
     * 한쪽만 있는 지점은 그 값만 담고 PEI 는 비운다 — 없는 값으로 나눌 수 없다.
     */
    private List<PowerEfficiencyTrendResponse.Point> join(Map<Long, Double> power,
                                                          Map<Long, Double> cpu) {
        List<Long> stamps = new ArrayList<>(new TreeSet<>(
                java.util.stream.Stream.concat(power.keySet().stream(), cpu.keySet().stream()).toList()));

        List<PowerEfficiencyTrendResponse.Point> points = new ArrayList<>(stamps.size());
        for (Long stamp : stamps) {
            Double watts = power.get(stamp);
            Double usage = cpu.get(stamp);
            Double pei = (watts != null && watts > 0 && usage != null) ? round(usage / watts) : null;
            points.add(new PowerEfficiencyTrendResponse.Point(
                    OffsetDateTime.ofInstant(Instant.ofEpochSecond(stamp), ZoneOffset.UTC),
                    watts == null ? null : round(watts),
                    usage == null ? null : round(usage),
                    pei));
        }
        return points;
    }

    /** 소수점 4자리. 부동소수 잔여값이 응답에 그대로 나가지 않게 한다. */
    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
