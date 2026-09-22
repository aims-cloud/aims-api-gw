package com.innogrid.aims.scheduler.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.entity.NodeExporterMapping;
import com.innogrid.aims.scheduler.metrics.SchedulerMetricsGateway;
import com.innogrid.aims.scheduler.model.ExporterInstanceCandidates;
import com.innogrid.aims.scheduler.model.ExporterInstances;
import com.innogrid.aims.scheduler.model.GpuTelemetry;
import com.innogrid.aims.scheduler.repository.NodeExporterMappingRepository;

/**
 * 익스포터 instance 해석의 한 자리 — 원장을 읽고 저장소에 실제로 있는 DCGM·IPMI instance 를 발견해
 * {@link ExporterInstances} 를 만든다.
 *
 * <p>노드 수집기·전력 최적화 추세·노드 전력 효율 추세 세 곳이 IPMI 시리즈를 노드 익스포터 instance 로 찾고 있었다.
 * 세 곳에 같은 규칙을 심으려면 규칙이 한 자리에 있어야 한다. 이 클래스는 원장과 발견 질의만 갖고, 해석 규칙은
 * {@link ExporterInstances} 에 있다.</p>
 *
 * <p>발견 질의는 {@code count by(instance) (…)} 두 개로 값은 쓰지 않고 라벨만 쓴다. 저장소가 답하지 않으면 빈
 * 집합이고, 그러면 해석은 원장과 "그대로" 로만 떨어진다 — 저장소가 멈춰도 수집이 멈추지 않는다.</p>
 */
@Service
public class ExporterInstanceResolver {

    private static final Logger log = LoggerFactory.getLogger(ExporterInstanceResolver.class);

    static final String DCGM_DISCOVERY = "count by(instance) (DCGM_FI_DEV_GPU_UTIL)";
    static final String IPMI_DISCOVERY = "count by(instance) (ipmi_dcmi_power_consumption_watts)";
    /** 지금 수집 대상인 노드 — 수집기와 같은 조건({@code up == 1}). 후보 API 가 배정을 계산할 때 쓴다. */
    static final String NODE_DISCOVERY = "up{job=\"%s\"} == 1";
    /** GPU 축 — 카드를 접는 함수가 값마다 다르다: 사용률은 평균, 온도는 가장 뜨거운 카드, 전력은 합. */
    static final String GPU_USAGE = "avg by(instance) (DCGM_FI_DEV_GPU_UTIL)";
    static final String GPU_TEMPERATURE = "max by(instance) (DCGM_FI_DEV_GPU_TEMP)";
    static final String GPU_POWER = "sum by(instance) (DCGM_FI_DEV_POWER_USAGE)";

    private final NodeExporterMappingRepository repository;
    private final SchedulerMetricsGateway metrics;
    private final MetricsQueryGuard guard;
    private final SchedulerProperties properties;

    @Autowired
    public ExporterInstanceResolver(NodeExporterMappingRepository repository,
                                    SchedulerMetricsGateway metrics,
                                    MetricsQueryGuard guard,
                                    SchedulerProperties properties) {
        this.repository = repository;
        this.metrics = metrics;
        this.guard = guard;
        this.properties = properties;
    }

    /** 원장 없이 조립하는 형태(시험) — 호스트명 일치와 그대로만 쓴다. */
    public ExporterInstanceResolver(SchedulerMetricsGateway metrics, MetricsQueryGuard guard, SchedulerProperties properties) {
        this(null, metrics, guard, properties);
    }

    /** 원장 스냅샷 — 호출 1회당 한 번만 읽는다. 원장이 없거나 비면 빈 목록. */
    public List<NodeExporterMapping> ledger() {
        return repository == null ? List.of() : repository.findAll();
    }

    /**
     * 호출자가 이미 손에 든 instance 집합으로 해석기를 만든다 — 구간 질의 결과의 키가 곧 "저장소에 있는 instance" 다.
     * 발견 질의를 다시 던지지 않는다.
     */
    public ExporterInstances resolve(Collection<String> dcgmSeen, Collection<String> ipmiSeen) {
        return ExporterInstances.of(ledger(), dcgmSeen, ipmiSeen);
    }

    /** 발견 질의로 두 집합을 채워 해석기를 만든다. 수집기와 단일 노드 추세가 쓴다. */
    public ExporterInstances discover() {
        return resolve(discoverGpuCounts().keySet(), discoverIpmiInstances());
    }

    /**
     * 노드별 GPU 개수 — <b>키는 DCGM 시리즈의 instance</b>. DCGM exporter 가 없으면 빈 맵이고 GPU 요구 워크로드는
     * 배치되지 않는다. 수집기가 GPU 개수와 DCGM 발견을 한 질의로 겸하려고 여기 둔다.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Integer> discoverGpuCounts() {
        Map<String, Integer> counts = new HashMap<>();
        if (metrics == null) return counts;
        try {
            List<Map<String, Object>> rows = guard.call(() -> metrics.queryResults(DCGM_DISCOVERY),
                    properties.getMetrics().getQueryTimeoutMs());
            for (Map<String, Object> result : rows) {
                Map<String, String> labels = (Map<String, String>) result.get("metric");
                List<Object> value = (List<Object>) result.get("value");
                if (labels == null || value == null || value.size() < 2) continue;
                String instance = labels.get("instance");
                if (instance == null) continue;
                counts.put(instance, (int) Double.parseDouble(value.get(1).toString()));
            }
        } catch (Exception e) {
            log.debug("GPU 개수 수집 실패: {}", e.getMessage());
        }
        return counts;
    }

    /**
     * GPU 축 발견 결과 — 노드별 값과, 왜 비었는지.
     *
     * @param byInstance      DCGM instance → 세 값. 그 메트릭이 없는 칸은 0
     * @param emptyMetrics    저장소가 빈 결과로 답한 메트릭 이름 — 익스포터가 그 카운터를 내지 않는다
     * @param timedOutMetrics 상한 안에 답하지 않은 메트릭 이름 — 익스포터가 아니라 저장소 쪽 문제
     */
    public record GpuTelemetryDiscovery(Map<String, GpuTelemetry> byInstance, List<String> emptyMetrics, List<String> timedOutMetrics) {
        public static final GpuTelemetryDiscovery NONE = new GpuTelemetryDiscovery(Map.of(), List.of(), List.of());
    }

    /**
     * 노드별 GPU 축 — <b>키는 DCGM 시리즈의 instance</b>. 클러스터 전체를 세 질의로 묻되 <b>동시에 던지고 한 상한만 기다린다</b>
     * — 순차로 기다리면 저장소가 반쯤 멈췄을 때 선행 단계가 세 상한(15초)을 삼켜 노드 수집 예산이 남지 않는다. 노드마다
     * 묻지 않는 것은 GPU 개수와 같은 결이다. 어느 메트릭이 비었는지·상한을 넘겼는지를 함께 돌려 수집기가 까닭을 적게 한다.
     */
    public GpuTelemetryDiscovery discoverGpuTelemetry() {
        if (metrics == null) return GpuTelemetryDiscovery.NONE;
        List<String> promQLs = List.of(GPU_USAGE, GPU_TEMPERATURE, GPU_POWER);
        List<java.util.concurrent.Future<List<Map<String, Object>>>> futures = new java.util.ArrayList<>();
        for (String q : promQLs) futures.add(guard.submit(() -> metrics.queryResults(q)));
        long timeout = properties.getMetrics().getQueryTimeoutMs();
        List<java.util.Optional<List<Map<String, Object>>>> rows = guard.awaitAll(futures, timeout,
                System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeout));

        List<Map<String, Double>> values = new java.util.ArrayList<>();
        List<String> empty = new java.util.ArrayList<>(), timedOut = new java.util.ArrayList<>();
        for (int i = 0; i < promQLs.size(); i++) {
            String metric = promQLs.get(i).replaceAll(".*\\((DCGM_\\w+)\\).*", "$1");
            if (rows.get(i).isEmpty()) { timedOut.add(metric); values.add(Map.of()); continue; }
            Map<String, Double> byInstance = instanceValues(rows.get(i).get());
            if (byInstance.isEmpty()) empty.add(metric);
            values.add(byInstance);
        }
        Set<String> instances = new LinkedHashSet<>();
        values.forEach(v -> instances.addAll(v.keySet()));
        Map<String, GpuTelemetry> out = new HashMap<>();
        for (String instance : instances) {
            out.put(instance, new GpuTelemetry(values.get(0).getOrDefault(instance, 0.0),
                    values.get(1).getOrDefault(instance, 0.0), values.get(2).getOrDefault(instance, 0.0)));
        }
        return new GpuTelemetryDiscovery(out, List.copyOf(empty), List.copyOf(timedOut));
    }

    /** {@code … by(instance)} 질의 결과 → instance → 값. 유한하지 않은 값(NaN·Inf)은 그 자리 미계측으로 뺀다. */
    @SuppressWarnings("unchecked")
    private static Map<String, Double> instanceValues(List<Map<String, Object>> rows) {
        Map<String, Double> out = new HashMap<>();
        if (rows == null) return out;
        for (Map<String, Object> result : rows) {
            Map<String, String> labels = (Map<String, String>) result.get("metric");
            List<Object> value = (List<Object>) result.get("value");
            if (labels == null || value == null || value.size() < 2) continue;
            String instance = labels.get("instance");
            if (instance == null) continue;
            try {
                double v = Double.parseDouble(value.get(1).toString());
                if (Double.isFinite(v)) out.put(instance, v);
            } catch (NumberFormatException ignored) {
                // 값이 숫자가 아니면 그 시리즈만 미계측
            }
        }
        return out;
    }

    /**
     * 익스포터 대응 화면이 고를 후보 — 세 발견 질의(DCGM·IPMI·노드)를 동시에 던져 한 상한 안에 받고
     * {@link ExporterInstanceCandidates} 를 만든다.
     *
     * <p>수집기용 {@link #discoverGpuCounts}·{@link #discoverIpmiInstances} 는 저장소가 답하지 않으면 빈 집합으로 떨어진다 —
     * 수집이 멈추지 않아야 하기 때문이다. 후보 API 는 반대로 <b>"없다" 와 "못 받았다" 를 구분해야</b> 하므로(빈 목록을 보고
     * 원장을 지우면 안 된다) 답하지 않은 질의를 {@code unavailable} 에 이름으로 남기고, 그 질의가 뒷받침하는 칸은 모델이
     * 비운다. 세 질의는 {@link #discoverGpuTelemetry} 와 같은 이유로 동시에 제출한다 — 차례로 기다리면 저장소가 느릴 때
     * 상한이 세 번 겹친다. 원장은 질의를 던지기 전에 한 번 읽는다.</p>
     */
    public ExporterInstanceCandidates candidates() {
        List<NodeExporterMapping> ledger = ledger();
        List<String> unavailable = new ArrayList<>();
        Map<String, Integer> dcgm = new HashMap<>();
        Set<String> ipmi = new LinkedHashSet<>();
        List<String> nodes = new ArrayList<>();
        if (metrics == null) {
            unavailable.addAll(List.of(ExporterInstanceCandidates.QUERY_DCGM, ExporterInstanceCandidates.QUERY_IPMI,
                    ExporterInstanceCandidates.QUERY_NODES));
            return ExporterInstanceCandidates.of(ledger, dcgm, ipmi, nodes, unavailable);
        }
        long timeout = properties.getMetrics().getQueryTimeoutMs();
        String nodeQuery = String.format(NODE_DISCOVERY, properties.getNodeJob());
        List<java.util.concurrent.Future<List<Map<String, Object>>>> futures = new ArrayList<>();
        for (String q : List.of(DCGM_DISCOVERY, IPMI_DISCOVERY, nodeQuery)) futures.add(guard.submit(() -> metrics.queryResults(q)));
        List<java.util.Optional<List<Map<String, Object>>>> rows = guard.awaitAll(futures, timeout,
                System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeout));

        if (rows.get(0).isPresent()) {
            for (Map<String, Object> row : rows.get(0).get()) {
                String instance = instanceOf(row);
                Integer count = seriesCountOf(row);
                if (instance != null && count != null) dcgm.put(instance, count);
            }
        } else {
            unavailable.add(ExporterInstanceCandidates.QUERY_DCGM);
        }
        if (rows.get(1).isPresent()) {
            for (Map<String, Object> row : rows.get(1).get()) {
                String instance = instanceOf(row);
                if (instance != null) ipmi.add(instance);
            }
        } else {
            unavailable.add(ExporterInstanceCandidates.QUERY_IPMI);
        }
        if (rows.get(2).isPresent()) {
            for (Map<String, Object> row : rows.get(2).get()) {
                String instance = instanceOf(row);
                if (instance != null) nodes.add(instance);
            }
        } else {
            unavailable.add(ExporterInstanceCandidates.QUERY_NODES);
        }
        if (!unavailable.isEmpty()) log.debug("후보 발견 질의가 답하지 않음: {}", unavailable);
        return ExporterInstanceCandidates.of(ledger, dcgm, ipmi, nodes, unavailable);
    }

    @SuppressWarnings("unchecked")
    private static String instanceOf(Map<String, Object> row) {
        Map<String, String> labels = row == null ? null : (Map<String, String>) row.get("metric");
        return labels == null ? null : labels.get("instance");
    }

    /** {@code count by(instance)} 한 행의 시리즈 수. 값이 없거나 수가 아니거나 유한하지 않으면 null — 그 행만 건너뛴다. */
    private static Integer seriesCountOf(Map<String, Object> row) {
        if (row == null || !(row.get("value") instanceof List<?> value) || value.size() < 2 || value.get(1) == null) return null;
        try {
            double d = Double.parseDouble(value.get(1).toString());
            if (Double.isNaN(d) || Double.isInfinite(d) || d < 0) return null;
            return (int) d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 저장소에 지금 있는 IPMI 시리즈의 instance 들. 값은 쓰지 않는다. */
    @SuppressWarnings("unchecked")
    public Set<String> discoverIpmiInstances() {
        Set<String> out = new LinkedHashSet<>();
        if (metrics == null) return out;
        try {
            List<Map<String, Object>> rows = guard.call(() -> metrics.queryResults(IPMI_DISCOVERY),
                    properties.getMetrics().getQueryTimeoutMs());
            for (Map<String, Object> result : rows) {
                Map<String, String> labels = (Map<String, String>) result.get("metric");
                if (labels == null) continue;
                String instance = labels.get("instance");
                if (instance != null) out.add(instance);
            }
        } catch (Exception e) {
            log.debug("IPMI instance 수집 실패: {}", e.getMessage());
        }
        return out;
    }
}
