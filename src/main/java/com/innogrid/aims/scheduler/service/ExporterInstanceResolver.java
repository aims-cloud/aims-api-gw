package com.innogrid.aims.scheduler.service;

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
     * 노드별 GPU 축 — <b>키는 DCGM 시리즈의 instance</b>. 클러스터 전체를 한 번씩 세 질의로 묻는다(노드마다 묻지 않는다 —
     * GPU 개수와 같은 결). 저장소에 그 메트릭이 없으면 그 칸만 0 이다.
     */
    public Map<String, GpuTelemetry> discoverGpuTelemetry() {
        Map<String, Double> usage = instanceValues(GPU_USAGE, "GPU 사용률");
        Map<String, Double> temperature = instanceValues(GPU_TEMPERATURE, "GPU 온도");
        Map<String, Double> power = instanceValues(GPU_POWER, "GPU 전력");
        Set<String> instances = new LinkedHashSet<>(usage.keySet());
        instances.addAll(temperature.keySet());
        instances.addAll(power.keySet());
        Map<String, GpuTelemetry> out = new HashMap<>();
        for (String instance : instances) {
            out.put(instance, new GpuTelemetry(usage.getOrDefault(instance, 0.0),
                    temperature.getOrDefault(instance, 0.0), power.getOrDefault(instance, 0.0)));
        }
        return out;
    }

    /** {@code … by(instance)} 질의 한 건 → instance → 값. 실패하면 빈 맵(부가 값이라 수집을 멈추지 않는다). */
    @SuppressWarnings("unchecked")
    private Map<String, Double> instanceValues(String promQL, String what) {
        Map<String, Double> out = new HashMap<>();
        if (metrics == null) return out;
        try {
            List<Map<String, Object>> rows = guard.call(() -> metrics.queryResults(promQL),
                    properties.getMetrics().getQueryTimeoutMs());
            for (Map<String, Object> result : rows) {
                Map<String, String> labels = (Map<String, String>) result.get("metric");
                List<Object> value = (List<Object>) result.get("value");
                if (labels == null || value == null || value.size() < 2) continue;
                String instance = labels.get("instance");
                if (instance == null) continue;
                out.put(instance, Double.parseDouble(value.get(1).toString()));
            }
        } catch (Exception e) {
            log.debug("{} 수집 실패: {}", what, e.getMessage());
        }
        return out;
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
