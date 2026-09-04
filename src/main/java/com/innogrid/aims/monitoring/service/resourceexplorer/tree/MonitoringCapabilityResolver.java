package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.innogrid.aims.monitoring.ResourceExplorerProperties;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Thanos 에서 트리 구성에 필요한 수집 상태를 한 번에 읽어 스냅샷으로 제공한다 (docs/06 §9.4).
 *
 * 호스트마다 질의하지 않고 전체 instance 에 대해 3개 쿼리만 수행한 뒤 instance 라벨로 조인한다.
 * instance 라벨 값은 monitoring_target 의 {@code address:port} 와 같다.
 *
 * <ul>
 *   <li>수집 상태: {@code up} — 1 이면 COLLECTING, 0 이거나 결과에 없으면 UNAVAILABLE.
 *       Prometheus 즉시 쿼리는 lookback(기본 5분) 안의 샘플만 돌려주므로 "최근 샘플 유효 시간" 판정이 자연히 포함된다.</li>
 *   <li>GPU 장치: DCGM_FI_DEV_GPU_UTIL 의 UUID·gpu 라벨 — 장치 메트릭이 없으면 GPU 그룹을 만들지 않는다 (NO_DATA).</li>
 *   <li>하드웨어 센서: ipmi_* 메트릭 이름 목록 — 센서별 메트릭이 없으면 해당 세부 항목을 만들지 않는다 (NO_DATA).</li>
 * </ul>
 * 스냅샷은 설정된 TTL(기본 1분) 동안 캐시하며, Thanos 장애 시 마지막 정상 스냅샷을 유지한다.
 */
@Component
public class MonitoringCapabilityResolver {

    private static final Logger log = LoggerFactory.getLogger(MonitoringCapabilityResolver.class);

    public static final String UP_QUERY = "max by (instance) (up)";
    public static final String GPU_DEVICE_QUERY = "count by (instance, UUID, gpu) (DCGM_FI_DEV_GPU_UTIL)";
    public static final String IPMI_METRIC_QUERY = "count by (instance, __name__) ({__name__=~\"ipmi_.*\"})";

    /** GPU 장치 하나 — 트리에서는 gpu 인덱스로 표시하고 UUID 를 resourceId 로 쓴다 */
    public record GpuDevice(String uuid, String index) {}

    /** 한 시점의 Thanos 수집 상태. degraded 는 Thanos 조회 실패로 빈 값이 채워졌음을 뜻한다. */
    public record Snapshot(Instant loadedAt,
                           Map<String, Boolean> upByInstance,
                           Map<String, List<GpuDevice>> gpuDevicesByInstance,
                           Map<String, Set<String>> ipmiMetricsByInstance,
                           boolean degraded) {

        static Snapshot empty(Instant at, boolean degraded) {
            return new Snapshot(at, Map.of(), Map.of(), Map.of(), degraded);
        }

        /** exporter 가 등록된 instance 의 수집 상태 — 등록 여부(NOT_CONFIGURED)는 호출측이 판단한다 */
        public MonitoringStatus statusOf(String instance) {
            Boolean up = upByInstance.get(instance);
            return up != null && up ? MonitoringStatus.COLLECTING : MonitoringStatus.UNAVAILABLE;
        }

        public List<GpuDevice> gpuDevices(String instance) {
            return gpuDevicesByInstance.getOrDefault(instance, List.of());
        }

        public boolean hasAnyMetric(String instance, List<String> metricNames) {
            Set<String> names = ipmiMetricsByInstance.get(instance);
            return names != null && metricNames.stream().anyMatch(names::contains);
        }
    }

    private final VictoriaMetricsClient client;
    private final ResourceExplorerProperties properties;
    private final Clock clock;

    private volatile Snapshot cached;

    @Autowired
    public MonitoringCapabilityResolver(VictoriaMetricsClient client, ResourceExplorerProperties properties) {
        this(client, properties, Clock.systemUTC());
    }

    public MonitoringCapabilityResolver(VictoriaMetricsClient client,
                                        ResourceExplorerProperties properties,
                                        Clock clock) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
    }

    /** 캐시가 유효하면 재사용하고, 만료됐으면 Thanos 를 다시 조회한다. */
    public Snapshot snapshot() {
        Snapshot current = cached;
        if (isFresh(current)) {
            return current;
        }
        synchronized (this) {
            current = cached;
            if (isFresh(current)) {
                return current;
            }
            Snapshot loaded = load(current);
            cached = loaded;
            return loaded;
        }
    }

    /** 캐시를 비워 다음 조회에서 Thanos 를 다시 읽게 한다 (타겟 등록·삭제 직후 등). */
    public void evict() {
        cached = null;
    }

    private boolean isFresh(Snapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        Duration ttl = properties.getMetricsCacheTtl();
        return snapshot.loadedAt().plus(ttl).isAfter(clock.instant());
    }

    private Snapshot load(Snapshot previous) {
        Instant now = clock.instant();
        try {
            Map<String, Boolean> up = new HashMap<>();
            for (Map<String, Object> row : client.queryResults(UP_QUERY)) {
                String instance = label(row, "instance");
                if (instance != null) {
                    up.put(instance, "1".equals(value(row)));
                }
            }
            Map<String, List<GpuDevice>> gpus = new HashMap<>();
            for (Map<String, Object> row : client.queryResults(GPU_DEVICE_QUERY)) {
                String instance = label(row, "instance");
                String uuid = label(row, "UUID");
                if (instance != null && uuid != null) {
                    gpus.computeIfAbsent(instance, k -> new ArrayList<>())
                            .add(new GpuDevice(uuid, label(row, "gpu")));
                }
            }
            gpus.values().forEach(list -> list.sort(MonitoringCapabilityResolver::compareGpu));
            Map<String, Set<String>> ipmi = new HashMap<>();
            for (Map<String, Object> row : client.queryResults(IPMI_METRIC_QUERY)) {
                String instance = label(row, "instance");
                String name = label(row, "__name__");
                if (instance != null && name != null) {
                    ipmi.computeIfAbsent(instance, k -> new HashSet<>()).add(name);
                }
            }
            return new Snapshot(now, up, gpus, ipmi, false);
        } catch (RuntimeException e) {
            if (previous != null) {
                // 마지막 정상 결과를 유지하되 TTL 만 갱신해 장애 중 Thanos 를 반복 호출하지 않는다
                log.warn("Thanos 수집 상태 조회 실패 — 마지막 정상 스냅샷을 유지합니다: {}", e.getMessage());
                return new Snapshot(now, previous.upByInstance(), previous.gpuDevicesByInstance(),
                        previous.ipmiMetricsByInstance(), previous.degraded());
            }
            log.warn("Thanos 수집 상태 조회 실패 — 모든 항목을 UNAVAILABLE 로 표시합니다: {}", e.getMessage());
            return Snapshot.empty(now, true);
        }
    }

    private static int compareGpu(GpuDevice a, GpuDevice b) {
        try {
            return Integer.compare(Integer.parseInt(a.index()), Integer.parseInt(b.index()));
        } catch (NumberFormatException e) {
            return String.valueOf(a.index()).compareTo(String.valueOf(b.index()));
        }
    }

    @SuppressWarnings("unchecked")
    private static String label(Map<String, Object> row, String name) {
        Object metric = row.get("metric");
        if (!(metric instanceof Map<?, ?> labels)) {
            return null;
        }
        Object v = ((Map<String, Object>) labels).get(name);
        return v == null ? null : v.toString();
    }

    private static String value(Map<String, Object> row) {
        Object value = row.get("value");
        if (value instanceof List<?> pair && pair.size() >= 2 && pair.get(1) != null) {
            return pair.get(1).toString();
        }
        return null;
    }
}
