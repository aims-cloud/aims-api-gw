package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import com.innogrid.aims.common.util.MetricFormat;
import com.innogrid.aims.monitoring.ThanosResults;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 물리 호스트 1대의 GPU 카테고리 패널 지표를 Thanos 의 dcgm_exporter 시계열에서 읽는다.
 *
 * <ul>
 *   <li>장치는 최근 24시간 안에 기대 지표가 한 번이라도 수집된 GPU 다 (트리의 GPU 장치 노드와 같은 창). 식별은 UUID, 표시는 {@code gpu} 인덱스.</li>
 *   <li>물리 GPU 단위만 다룬다 — MIG 인스턴스 시계열({@code GPU_I_ID} 라벨)은 {@link #PHYSICAL} 매처로 뺀다.</li>
 *   <li>현재값은 즉시 쿼리(기본 조회 창 5분), 평균은 최근 1시간 {@code avg_over_time}. VRAM 은 MB 로 읽는다.</li>
 *   <li>dcgm_exporter 기본 수집 항목에 없는 값(팬 속도 등)은 시계열이 없으면 null 이다.</li>
 *   <li>상태 요약용 지표: ECC DBE(DCGM_FI_DEV_ECC_DBE_VOL_TOTAL)·Clock 이벤트(DCGM_FI_DEV_CLOCKS_EVENT_REASONS)는 기본 수집 항목이 아니고,
 *       PCIe Replay(DCGM_FI_DEV_PCIE_REPLAY_COUNTER)는 기본 수집 항목이다. 누적 카운터는 {@code increase()} 로 구간 증가를 구한다.</li>
 * </ul>
 * 자원은 {@link MetricSelector}(dcgm_exporter instance)로 가리키고, Thanos 장애는 빈 목록(미수집)으로 돌려준다.
 */
@Component
public class HostGpuMetricsQueryService {

    private static final Logger log = LoggerFactory.getLogger(HostGpuMetricsQueryService.class);

    /** MIG 인스턴스 시계열 제외 — 라벨이 없거나 빈 값인 물리 GPU 시계열만 */
    static final String PHYSICAL = "GPU_I_ID=\"\"";
    static final String PRESENCE_WINDOW = "1d";
    static final String AVG_WINDOW = "1h";
    /** 수집 주기(초) — deploy/monitoring/prometheus.yml scrape_interval */
    public static final int COLLECT_INTERVAL_SECONDS = 15;

    /** 수집 상태 판정의 기대 지표 — dcgm_exporter 기본 수집 항목 중 패널·카탈로그가 쓰는 지표 (docs/03 §3.2) */
    public static final List<String> EXPECTED_METRICS = List.of(
            "DCGM_FI_DEV_GPU_UTIL",
            "DCGM_FI_DEV_MEM_COPY_UTIL",
            "DCGM_FI_DEV_FB_USED",
            "DCGM_FI_DEV_FB_FREE",
            "DCGM_FI_DEV_GPU_TEMP",
            "DCGM_FI_DEV_POWER_USAGE",
            "DCGM_FI_DEV_SM_CLOCK");

    private final VictoriaMetricsClient client;

    public HostGpuMetricsQueryService(VictoriaMetricsClient client) {
        this.client = client;
    }

    /**
     * GPU 1장의 패널 값. 현재 수집이 끊긴 GPU 는 현재값·평균이 null 이고 collectedMetrics 가 비어 있다.
     * driverVersion 은 dcgm_exporter 가 {@code DCGM_FI_DRIVER_VERSION} 라벨을 붙일 때만 채워진다.
     */
    public record GpuSample(String uuid, String index, String model, String pciBusId, String driverVersion,
                            Instant lastCollectedAt, Set<String> collectedMetrics,
                            Double utilization, Double utilizationAvg,
                            Double fbUsedMb, Double fbFreeMb, Double vramPercentAvg,
                            Double temperatureC, Double powerWatts, Double fanSpeedPercent) {

        public GpuSample {
            collectedMetrics = collectedMetrics == null ? Set.of() : Set.copyOf(collectedMetrics);
        }

        /** 기대 지표가 지금 하나라도 수집되는지 */
        public boolean collecting() {
            return !collectedMetrics.isEmpty();
        }

        public Double fbTotalMb() {
            return fbUsedMb == null || fbFreeMb == null ? null : fbUsedMb + fbFreeMb;
        }

        public Double vramPercent() {
            Double total = fbTotalMb();
            return total == null || total <= 0 ? null : fbUsedMb / total * 100;
        }

        /** 트리 GPU 장치 노드와 같은 표시 이름 — "GPU {인덱스}", 인덱스가 없으면 UUID */
        public String displayName() {
            return "GPU " + (index == null ? uuid : index);
        }
    }

    /** GPU 1장의 시계열 */
    public record GpuSeries(String uuid, String index, List<DataPoint> points) {}

    /**
     * GPU 1장의 상태 요약 값. 시계열이 없으면 null(미수집).
     * eccDbe 는 드라이버 적재 이후 누적(volatile), PCIe Replay 는 누적값과 최근 1시간·24시간 증가, clocksMask 는 활성 이벤트 비트마스크.
     */
    public record GpuHealth(Double eccDbe, Double pcieReplayTotal, Double pcieReplay1h, Double pcieReplay24h,
                            Long clocksMask) {}

    // ── PromQL ──────────────────────────────────────────────────

    static String expectedMetricMatcher() {
        return "__name__=~\"" + String.join("|", EXPECTED_METRICS) + "\"";
    }

    /**
     * 최근 24시간 안에 수집된 GPU 와 마지막 수집 시각(초) — 기대 지표별 실제 표본 시각 최댓값을 {@code or} 로 잇는다.
     * timestamp() 가 지표 이름을 지우므로 여러 지표를 한 선택자로 넣으면 같은 라벨 조합이 겹쳐 Thanos 가 거부한다.
     * {@code or} 는 앞 지표에 있는 GPU 를 우선하므로 GPU_UTIL 기준이고, 그게 없을 때만 다음 지표의 시각을 쓴다.
     */
    static String presenceQuery(MetricSelector s) {
        String perMetric = EXPECTED_METRICS.stream()
                .map(m -> "max_over_time(timestamp(" + m + s.braces(PHYSICAL) + ")[" + PRESENCE_WINDOW + ":1m])")
                .collect(Collectors.joining(" or "));
        return "max by (UUID, gpu, modelName, pci_bus_id, DCGM_FI_DRIVER_VERSION) (" + perMetric + ")";
    }

    /** 지금 수집되는 기대 지표 — GPU·지표 이름별 시계열 수 */
    static String collectedQuery(MetricSelector s) {
        return "count by (UUID, __name__) (" + s.braces(expectedMetricMatcher() + "," + PHYSICAL) + ")";
    }

    static String currentQuery(String metric, MetricSelector s) {
        return "max by (UUID) (" + metric + s.braces(PHYSICAL) + ")";
    }

    static String averageQuery(String metric, MetricSelector s) {
        return "max by (UUID) (avg_over_time(" + metric + s.braces(PHYSICAL) + "[" + AVG_WINDOW + "]))";
    }

    /** 평균 VRAM 사용률 — 전체 용량(used+free)은 고정이라 평균 used / 평균 전체와 같다 */
    static String vramPercentAverageQuery(MetricSelector s) {
        String used = averageQuery("DCGM_FI_DEV_FB_USED", s);
        String free = averageQuery("DCGM_FI_DEV_FB_FREE", s);
        return used + " / (" + used + " + " + free + ") * 100";
    }

    /** 차트 쿼리 — GPU(UUID, gpu)별 시계열. uuid 가 있으면 그 GPU 1장만 */
    static String trendQuery(GpuMetric metric, MetricSelector s, String uuid) {
        String matchers = uuid == null ? PHYSICAL : PHYSICAL + "," + MetricSelector.label("UUID", uuid);
        String by = "max by (UUID, gpu) ";
        String used = by + "(DCGM_FI_DEV_FB_USED" + s.braces(matchers) + ")";
        return switch (metric) {
            case GPU_UTILIZATION -> by + "(DCGM_FI_DEV_GPU_UTIL" + s.braces(matchers) + ")";
            case VRAM_UTILIZATION -> used + " / (" + used + " + " + by + "(DCGM_FI_DEV_FB_FREE" + s.braces(matchers) + ")) * 100";
            case VRAM_USED -> used + " / 1024";
            case GPU_POWER -> by + "(DCGM_FI_DEV_POWER_USAGE" + s.braces(matchers) + ")";
            case GPU_TEMPERATURE -> by + "(DCGM_FI_DEV_GPU_TEMP" + s.braces(matchers) + ")";
            case FAN_SPEED -> by + "(DCGM_FI_DEV_FAN_SPEED" + s.braces(matchers) + ")";
        };
    }

    static String increaseQuery(String metric, MetricSelector s, String window) {
        return "max by (UUID) (increase(" + metric + s.braces(PHYSICAL) + "[" + window + "]))";
    }

    /** Clock 이벤트 비트마스크 — DCGM 3.3 에서 이름이 CLOCK_THROTTLE_REASONS → CLOCKS_EVENT_REASONS 로 바뀌어 둘 다 읽는다 */
    static String clocksQuery(MetricSelector s) {
        return currentQuery("DCGM_FI_DEV_CLOCKS_EVENT_REASONS", s) + " or " + currentQuery("DCGM_FI_DEV_CLOCK_THROTTLE_REASONS", s);
    }

    /** MIG 인스턴스가 있는 GPU — dcgm_exporter 는 MIG 인스턴스 시계열에 GPU_I_ID 를 붙인다 */
    static String migQuery(MetricSelector s) {
        return "count by (UUID) (DCGM_FI_DEV_FB_USED" + s.braces("GPU_I_ID!=\"\"") + ")";
    }

    /** PCIe Replay 시간당 증가 시계열 — GPU(UUID, gpu)별. uuid 가 있으면 그 GPU 1장만 */
    static String pcieReplayTrendQuery(MetricSelector s, String uuid) {
        String matchers = uuid == null ? PHYSICAL : PHYSICAL + "," + MetricSelector.label("UUID", uuid);
        return "max by (UUID, gpu) (increase(DCGM_FI_DEV_PCIE_REPLAY_COUNTER" + s.braces(matchers) + "[1h]))";
    }

    // ── 조회 ────────────────────────────────────────────────────

    /** 최근 24시간 안에 수집된 GPU 목록 (인덱스순). 장애면 빈 목록 */
    public List<GpuSample> devices(MetricSelector s) {
        try {
            Map<String, Builder> gpus = new LinkedHashMap<>();
            for (Map<String, Object> row : client.queryResults(presenceQuery(s))) {
                Map<String, String> labels = ThanosResults.labels(row);
                String uuid = labels.get("UUID");
                Double seconds = ThanosResults.value(row);
                if (uuid == null || seconds == null) {
                    continue;
                }
                Builder b = gpus.computeIfAbsent(uuid, k -> new Builder(uuid));
                Instant seen = Instant.ofEpochMilli(Math.round(seconds * 1000));
                if (b.lastCollectedAt == null || seen.isAfter(b.lastCollectedAt)) {
                    // 같은 GPU 가 24시간 안에 라벨을 바꿨으면(드라이버 갱신 등) 마지막 라벨을 쓴다
                    b.lastCollectedAt = seen;
                    b.index = labels.get("gpu");
                    b.model = labels.get("modelName");
                    b.pciBusId = labels.get("pci_bus_id");
                    b.driverVersion = labels.get("DCGM_FI_DRIVER_VERSION");
                }
            }
            if (gpus.isEmpty()) {
                return List.of();
            }
            for (Map<String, Object> row : client.queryResults(collectedQuery(s))) {
                Map<String, String> labels = ThanosResults.labels(row);
                Builder b = gpus.get(labels.get("UUID"));
                if (b != null && labels.get("__name__") != null) {
                    b.collected.add(labels.get("__name__"));
                }
            }
            merge(gpus, currentQuery("DCGM_FI_DEV_GPU_UTIL", s), (b, v) -> b.utilization = v);
            merge(gpus, averageQuery("DCGM_FI_DEV_GPU_UTIL", s), (b, v) -> b.utilizationAvg = v);
            merge(gpus, currentQuery("DCGM_FI_DEV_FB_USED", s), (b, v) -> b.fbUsed = v);
            merge(gpus, currentQuery("DCGM_FI_DEV_FB_FREE", s), (b, v) -> b.fbFree = v);
            merge(gpus, vramPercentAverageQuery(s), (b, v) -> b.vramPercentAvg = v);
            merge(gpus, currentQuery("DCGM_FI_DEV_GPU_TEMP", s), (b, v) -> b.temperature = v);
            merge(gpus, currentQuery("DCGM_FI_DEV_POWER_USAGE", s), (b, v) -> b.power = v);
            merge(gpus, currentQuery("DCGM_FI_DEV_FAN_SPEED", s), (b, v) -> b.fan = v);
            return gpus.values().stream()
                    .map(Builder::build)
                    .sorted(Comparator.comparing(GpuSample::index, HostGpuMetricsQueryService::compareIndex)
                            .thenComparing(GpuSample::uuid))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("Thanos GPU 지표 조회 실패 — GPU 를 미수집으로 표시합니다: {}", e.getMessage());
            return List.of();
        }
    }

    /** 상태 요약 값 — UUID 별. 지표가 없는 GPU 는 맵에 없거나 해당 값이 null 이다. 장애면 빈 맵 */
    public Map<String, GpuHealth> health(MetricSelector s) {
        try {
            Map<String, Double> ecc = byUuid(currentQuery("DCGM_FI_DEV_ECC_DBE_VOL_TOTAL", s));
            Map<String, Double> pcieTotal = byUuid(currentQuery("DCGM_FI_DEV_PCIE_REPLAY_COUNTER", s));
            Map<String, Double> pcie1h = byUuid(increaseQuery("DCGM_FI_DEV_PCIE_REPLAY_COUNTER", s, "1h"));
            Map<String, Double> pcie24h = byUuid(increaseQuery("DCGM_FI_DEV_PCIE_REPLAY_COUNTER", s, "24h"));
            Map<String, Double> clocks = byUuid(clocksQuery(s));

            Set<String> uuids = new LinkedHashSet<>();
            List.of(ecc, pcieTotal, pcie1h, pcie24h, clocks).forEach(m -> uuids.addAll(m.keySet()));
            Map<String, GpuHealth> out = new LinkedHashMap<>();
            for (String uuid : uuids) {
                Double mask = clocks.get(uuid);
                out.put(uuid, new GpuHealth(ecc.get(uuid), pcieTotal.get(uuid), pcie1h.get(uuid), pcie24h.get(uuid),
                        mask == null ? null : Math.round(mask)));
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("Thanos GPU 상태 지표 조회 실패 — 상태 카드를 미수집으로 표시합니다: {}", e.getMessage());
            return Map.of();
        }
    }

    /** MIG 로 나뉜 GPU 의 UUID — 장애면 빈 집합(MIG 아님으로 본다) */
    public Set<String> migUuids(MetricSelector s) {
        try {
            return Set.copyOf(byUuid(migQuery(s)).keySet());
        } catch (RuntimeException e) {
            log.warn("Thanos GPU MIG 조회 실패 — MIG 구분 없이 표시합니다: {}", e.getMessage());
            return Set.of();
        }
    }

    /** 지표별 시계열 — GPU 별 1개, 인덱스순. 값은 소수 1자리, 미수집·장애는 빈 목록 */
    public List<GpuSeries> trend(GpuMetric metric, MetricSelector s, String uuid, Instant from, Instant to, String step) {
        return series(trendQuery(metric, s, uuid), from, to, step);
    }

    /** PCIe Replay 시간당 증가(회/h) 시계열 — GPU 별 1개, 인덱스순. 미수집·장애는 빈 목록 */
    public List<GpuSeries> pcieReplayTrend(MetricSelector s, String uuid, Instant from, Instant to) {
        return series(pcieReplayTrendQuery(s, uuid), from, to, "1h");
    }

    private List<GpuSeries> series(String query, Instant from, Instant to, String step) {
        try {
            List<GpuSeries> out = new ArrayList<>();
            for (Map<String, Object> result : client.queryRangeResults(query, from, to, step)) {
                Map<String, String> labels = ThanosResults.labels(result);
                List<DataPoint> points = new ArrayList<>();
                for (ThanosResults.Sample sample : ThanosResults.values(result)) {
                    points.add(new DataPoint(sample.epochSecond(),
                            MetricFormat.round1(sample.value())));
                }
                if (!points.isEmpty()) {
                    out.add(new GpuSeries(labels.get("UUID"), labels.get("gpu"), points));
                }
            }
            out.sort(Comparator.comparing(GpuSeries::index, HostGpuMetricsQueryService::compareIndex));
            return out;
        } catch (RuntimeException e) {
            log.warn("Thanos GPU 차트 조회 실패 — 빈 시계열을 돌려줍니다: {}", e.getMessage());
            return List.of();
        }
    }

    // ── 내부 ────────────────────────────────────────────────────

    /** gpu 인덱스 비교 — 숫자는 숫자순("2" < "10")으로 앞에, 숫자가 아닌 값은 그 뒤에 문자열순, null 은 마지막 */
    public static int compareIndex(String a, String b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : 1) : -1;
        }
        Integer na = number(a);
        Integer nb = number(b);
        if (na != null && nb != null) {
            return Integer.compare(na, nb);
        }
        if (na != null || nb != null) {
            return na != null ? -1 : 1;
        }
        return a.compareTo(b);
    }

    private static Integer number(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 즉시 쿼리 결과를 UUID → 값으로. 값이 없거나 비수치인 행은 뺀다 */
    private Map<String, Double> byUuid(String query) {
        Map<String, Double> out = new HashMap<>();
        for (Map<String, Object> row : client.queryResults(query)) {
            String uuid = ThanosResults.labels(row).get("UUID");
            Double v = ThanosResults.value(row);
            if (uuid != null && v != null) {
                out.putIfAbsent(uuid, v);
            }
        }
        return out;
    }

    private void merge(Map<String, Builder> gpus, String query, BiConsumer<Builder, Double> setter) {
        for (Map<String, Object> row : client.queryResults(query)) {
            Builder b = gpus.get(ThanosResults.labels(row).get("UUID"));
            Double v = ThanosResults.value(row);
            if (b != null && v != null) {
                setter.accept(b, v);
            }
        }
    }

    private static final class Builder {
        final String uuid;
        final Set<String> collected = new LinkedHashSet<>();
        String index;
        String model;
        String pciBusId;
        String driverVersion;
        Instant lastCollectedAt;
        Double utilization;
        Double utilizationAvg;
        Double fbUsed;
        Double fbFree;
        Double vramPercentAvg;
        Double temperature;
        Double power;
        Double fan;

        Builder(String uuid) {
            this.uuid = uuid;
        }

        GpuSample build() {
            return new GpuSample(uuid, index, model, pciBusId, driverVersion, lastCollectedAt, collected,
                    utilization, utilizationAvg, fbUsed, fbFree, vramPercentAvg, temperature, power, fan);
        }
    }
}
