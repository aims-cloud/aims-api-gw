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
 * Thanos 에서 트리 구성에 필요한 수집 상태를 한 번에 읽어 스냅샷으로 제공한다 (docs/06 §5.1, §9.4).
 *
 * 호스트·VM 마다 질의하지 않고 전체에 대해 9개 쿼리만 수행한 뒤 라벨로 조인한다.
 * <ul>
 *   <li>물리 호스트(1세부): {@code instance} 라벨 = monitoring_target 의 {@code address:port}</li>
 *   <li>Tumblebug VM(2세부): exporter 등록 시 relabel 로 심는 {@code tb_ns / tb_infra / tb_node} 라벨 (docs/06 §9.6).
 *       IP 로 대조하지 않는다.</li>
 * </ul>
 *
 * "존재"와 "현재 상태"를 분리한다 — 일시적 수집 실패만으로 기존 항목을 제거하지 않기 위해서다.
 * <ul>
 *   <li>존재(항목 생성 여부): GPU 장치·하드웨어 센서·Tumblebug VM 등록·node_exporter 항목별 지표군은
 *       {@code count_over_time(...[presenceWindow])}(기본 24시간)로 판단한다. exporter 가 죽어 즉시 쿼리에서 사라져도
 *       하루 안에 수집 이력이 있으면 항목을 유지한다. node_exporter 항목은 exporter 가 살아 있는데 그 항목 지표만 없으면
 *       NO_DATA 로 표시한다(Service Status 는 systemd 콜렉터, Swap 은 SwapTotal &gt; 0 이 있어야 한다).</li>
 *   <li>현재 상태: {@code up} 즉시 쿼리 — 1 이면 COLLECTING, 0 이거나 lookback(5분) 안에 샘플이 없으면 UNAVAILABLE.</li>
 * </ul>
 * 스냅샷은 설정된 TTL(기본 1분) 동안 캐시하며, Thanos 장애 시 마지막 정상 스냅샷을 유지한다.
 */
@Component
public class MonitoringCapabilityResolver {

    private static final Logger log = LoggerFactory.getLogger(MonitoringCapabilityResolver.class);

    /** 존재 판정 기본 범위 — 설정(aims.resource-explorer.metrics-presence-window) 기본값과 같다 */
    public static final String DEFAULT_PRESENCE_WINDOW = "24h";

    public static final String UP_QUERY = "max by (instance) (up)";
    /** IPMI 섀시 전원 상태 (1 켜짐 / 0 꺼짐) — 물리 호스트 실행 상태(RUNNING/STOP) 판정에 사용 */
    public static final String CHASSIS_POWER_QUERY = "max by (instance) (ipmi_chassis_power_state)";
    /** Tumblebug VM 현재 수집 상태 — relabel 라벨(tb_ns/tb_infra/tb_node)이 있는 시계열만 */
    public static final String TB_UP_QUERY = "max by (tb_ns, tb_infra, tb_node) (up{tb_node!=\"\"})";

    /**
     * node_exporter 항목별 존재 판정 지표 (docs/06 §5.2 항목 ↔ 대표 지표). 하나라도 표본이 있으면 그 항목은 데이터가 있다.
     * Swap 은 별도 — 지표는 항상 있지만 SwapTotal 이 0 이면 의미가 없어 {@link #SWAP_TOTAL_METRIC} 값 &gt; 0 으로 판정한다.
     */
    public static final List<String> NODE_PRESENCE_METRICS = List.of(
            "node_cpu_seconds_total", "node_memory_MemTotal_bytes", "node_disk_read_bytes_total",
            "node_filesystem_size_bytes", "node_network_receive_bytes_total", "node_procs_running",
            "node_systemd_unit_state", "node_load1");
    public static final String SWAP_TOTAL_METRIC = "node_memory_SwapTotal_bytes";

    /** GPU 장치 존재 — presenceWindow 안에 한 번이라도 수집된 (instance, UUID, gpu) */
    public static String gpuDeviceQuery(String window) {
        return "count by (instance, UUID, gpu) (count_over_time(DCGM_FI_DEV_GPU_UTIL[" + window + "]))";
    }

    /**
     * 하드웨어 센서 메트릭 존재 — presenceWindow 안에 한 번이라도 수집된 (instance, 메트릭 이름).
     * range 함수는 __name__ 을 버리므로 label_replace 로 metric 라벨에 옮긴 뒤 서브쿼리로 집계한다.
     */
    public static String ipmiMetricQuery(String window) {
        return "count by (instance, metric) (count_over_time(label_replace({__name__=~\"ipmi_.*\"}, \"metric\", \"$1\", \"__name__\", \"(.*)\")["
                + window + ":5m]))";
    }

    /**
     * node_exporter 항목별 지표군 존재 — presenceWindow 안에 한 번이라도 수집된 (instance, tb 라벨, 메트릭 이름).
     * 물리 호스트와 Tumblebug VM 시계열을 한 번에 읽는다(VM 시계열은 instance 와 tb_* 라벨을 모두 가진다).
     */
    public static String nodeMetricQuery(String window) {
        return "count by (instance, tb_ns, tb_infra, tb_node, metric) (count_over_time(label_replace({__name__=~\""
                + String.join("|", NODE_PRESENCE_METRICS) + "\"}, \"metric\", \"$1\", \"__name__\", \"(.*)\")["
                + window + ":5m]))";
    }

    /** Swap 존재 — presenceWindow 안의 SwapTotal 최대값. 0 이면 스왑이 없는 호스트라 Swap 항목은 NO_DATA */
    public static String swapPresenceQuery(String window) {
        return "max by (instance, tb_ns, tb_infra, tb_node) (max_over_time(" + SWAP_TOTAL_METRIC + "[" + window + "]))";
    }

    /** Tumblebug VM exporter 등록 여부 — presenceWindow 안에 up 시계열이 한 번이라도 있었던 VM */
    public static String tbRegisteredQuery(String window) {
        return "max by (tb_ns, tb_infra, tb_node) (count_over_time(up{tb_node!=\"\"}[" + window + "]))";
    }

    public static String tbGpuDeviceQuery(String window) {
        return "count by (tb_ns, tb_infra, tb_node, UUID, gpu) (count_over_time(DCGM_FI_DEV_GPU_UTIL{tb_node!=\"\"}[" + window + "]))";
    }

    /** GPU 장치 하나 — 트리에서는 gpu 인덱스로 표시하고 UUID 를 resourceId 로 쓴다 */
    public record GpuDevice(String uuid, String index) {}

    /** 한 시점의 Thanos 수집 상태. degraded 는 정상 스냅샷이 한 번도 없는 상태에서 조회가 실패해 빈 값이 채워졌음을 뜻한다. */
    public record Snapshot(Instant loadedAt,
                           Map<String, Boolean> upByInstance,
                           Map<String, List<GpuDevice>> gpuDevicesByInstance,
                           Map<String, Set<String>> ipmiMetricsByInstance,
                           Map<String, Boolean> chassisPowerOnByInstance,
                           Map<String, Boolean> upByTbNode,
                           Set<String> registeredTbNodes,
                           Map<String, List<GpuDevice>> gpuDevicesByTbNode,
                           Map<String, Set<String>> nodeMetricsByInstance,
                           Map<String, Set<String>> nodeMetricsByTbNode,
                           boolean degraded) {

        /** node_exporter 항목 지표군 정보 없이 만드는 생성자 — 항목 NO_DATA 판정을 쓰지 않는 호출자(역할 스코프 등)용 */
        public Snapshot(Instant loadedAt,
                        Map<String, Boolean> upByInstance,
                        Map<String, List<GpuDevice>> gpuDevicesByInstance,
                        Map<String, Set<String>> ipmiMetricsByInstance,
                        Map<String, Boolean> chassisPowerOnByInstance,
                        Map<String, Boolean> upByTbNode,
                        Set<String> registeredTbNodes,
                        Map<String, List<GpuDevice>> gpuDevicesByTbNode,
                        boolean degraded) {
            this(loadedAt, upByInstance, gpuDevicesByInstance, ipmiMetricsByInstance, chassisPowerOnByInstance,
                    upByTbNode, registeredTbNodes, gpuDevicesByTbNode, Map.of(), Map.of(), degraded);
        }

        static Snapshot empty(Instant at, boolean degraded) {
            return new Snapshot(at, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Map.of(), Map.of(), Map.of(), degraded);
        }

        /** IPMI 섀시 전원 — true 켜짐, false 꺼짐, null 미확인(ipmi_exporter 없음 또는 샘플 없음) */
        public Boolean chassisPowerOn(String ipmiInstance) {
            return chassisPowerOnByInstance.get(ipmiInstance);
        }

        /** exporter 가 등록된 instance 의 현재 수집 상태 — 등록 여부(NOT_CONFIGURED)는 호출측이 판단한다 */
        public MonitoringStatus statusOf(String instance) {
            Boolean up = upByInstance.get(instance);
            return up != null && up ? MonitoringStatus.COLLECTING : MonitoringStatus.UNAVAILABLE;
        }

        /** presenceWindow 안에 확인된 GPU 장치 — 일시 장애 중에도 유지된다 */
        public List<GpuDevice> gpuDevices(String instance) {
            return gpuDevicesByInstance.getOrDefault(instance, List.of());
        }

        /** presenceWindow 안에 확인된 하드웨어 센서 메트릭 존재 여부 */
        public boolean hasAnyMetric(String instance, List<String> metricNames) {
            Set<String> names = ipmiMetricsByInstance.get(instance);
            return names != null && metricNames.stream().anyMatch(names::contains);
        }

        /** presenceWindow 안에 확인된 물리 호스트(instance)의 node_exporter 지표군 — 없으면 빈 집합 */
        public Set<String> nodeMetrics(String instance) {
            return nodeMetricsByInstance.getOrDefault(instance, Set.of());
        }

        /** presenceWindow 안에 확인된 Tumblebug VM 의 node_exporter 지표군 — 없으면 빈 집합 */
        public Set<String> tbNodeMetrics(String nsId, String infraId, String nodeId) {
            return nodeMetricsByTbNode.getOrDefault(tbKey(nsId, infraId, nodeId), Set.of());
        }

        /**
         * Tumblebug VM 의 수집 상태. Tumblebug 은 exporter 등록 원장이 없으므로 presenceWindow 안의 up 시계열 존재로
         * 등록 여부를 판단한다 — 이력 없음: NOT_CONFIGURED, 현재 up=1: COLLECTING, 그 외(up=0·샘플 없음): UNAVAILABLE.
         */
        public MonitoringStatus tbStatusOf(String nsId, String infraId, String nodeId) {
            String key = tbKey(nsId, infraId, nodeId);
            Boolean up = upByTbNode.get(key);
            if (up == null && !registeredTbNodes.contains(key)) {
                return MonitoringStatus.NOT_CONFIGURED;
            }
            return up != null && up ? MonitoringStatus.COLLECTING : MonitoringStatus.UNAVAILABLE;
        }

        public List<GpuDevice> tbGpuDevices(String nsId, String infraId, String nodeId) {
            return gpuDevicesByTbNode.getOrDefault(tbKey(nsId, infraId, nodeId), List.of());
        }

        static String tbKey(String nsId, String infraId, String nodeId) {
            return nsId + "/" + infraId + "/" + nodeId;
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
        String window = promDuration(properties.getMetricsPresenceWindow());
        try {
            Map<String, Boolean> up = new HashMap<>();
            for (Map<String, Object> row : client.queryResults(UP_QUERY)) {
                String instance = label(row, "instance");
                if (instance != null) {
                    up.put(instance, "1".equals(value(row)));
                }
            }
            Map<String, List<GpuDevice>> gpus = new HashMap<>();
            for (Map<String, Object> row : client.queryResults(gpuDeviceQuery(window))) {
                String instance = label(row, "instance");
                String uuid = label(row, "UUID");
                if (instance != null && uuid != null) {
                    gpus.computeIfAbsent(instance, k -> new ArrayList<>()).add(new GpuDevice(uuid, label(row, "gpu")));
                }
            }
            Map<String, Set<String>> ipmi = new HashMap<>();
            for (Map<String, Object> row : client.queryResults(ipmiMetricQuery(window))) {
                String instance = label(row, "instance");
                String name = label(row, "metric");
                if (instance != null && name != null) {
                    ipmi.computeIfAbsent(instance, k -> new HashSet<>()).add(name);
                }
            }
            Map<String, Boolean> power = new HashMap<>();
            for (Map<String, Object> row : client.queryResults(CHASSIS_POWER_QUERY)) {
                String instance = label(row, "instance");
                if (instance != null) {
                    power.put(instance, "1".equals(value(row)));
                }
            }
            Map<String, Boolean> tbUp = new HashMap<>();
            for (Map<String, Object> row : client.queryResults(TB_UP_QUERY)) {
                String key = tbKey(row);
                if (key != null) {
                    tbUp.put(key, "1".equals(value(row)));
                }
            }
            Set<String> tbRegistered = new HashSet<>();
            for (Map<String, Object> row : client.queryResults(tbRegisteredQuery(window))) {
                String key = tbKey(row);
                if (key != null) {
                    tbRegistered.add(key);
                }
            }
            Map<String, List<GpuDevice>> tbGpus = new HashMap<>();
            for (Map<String, Object> row : client.queryResults(tbGpuDeviceQuery(window))) {
                String key = tbKey(row);
                String uuid = label(row, "UUID");
                if (key != null && uuid != null) {
                    tbGpus.computeIfAbsent(key, k -> new ArrayList<>()).add(new GpuDevice(uuid, label(row, "gpu")));
                }
            }
            Map<String, Set<String>> nodeMetrics = new HashMap<>();
            Map<String, Set<String>> tbNodeMetrics = new HashMap<>();
            for (Map<String, Object> row : client.queryResults(nodeMetricQuery(window))) {
                String name = label(row, "metric");
                if (name != null) {
                    addNodeMetric(row, name, nodeMetrics, tbNodeMetrics);
                }
            }
            for (Map<String, Object> row : client.queryResults(swapPresenceQuery(window))) {
                if (isPositive(value(row))) {
                    addNodeMetric(row, SWAP_TOTAL_METRIC, nodeMetrics, tbNodeMetrics);
                }
            }
            gpus.values().forEach(list -> list.sort(MonitoringCapabilityResolver::compareGpu));
            tbGpus.values().forEach(list -> list.sort(MonitoringCapabilityResolver::compareGpu));
            return new Snapshot(now, up, gpus, ipmi, power, tbUp, tbRegistered, tbGpus, nodeMetrics, tbNodeMetrics, false);
        } catch (RuntimeException e) {
            if (previous != null) {
                // 마지막 정상 결과를 유지하되 TTL 만 갱신해 장애 중 Thanos 를 반복 호출하지 않는다
                log.warn("Thanos 수집 상태 조회 실패 — 마지막 정상 스냅샷을 유지합니다: {}", e.getMessage());
                return new Snapshot(now, previous.upByInstance(), previous.gpuDevicesByInstance(),
                        previous.ipmiMetricsByInstance(), previous.chassisPowerOnByInstance(),
                        previous.upByTbNode(), previous.registeredTbNodes(), previous.gpuDevicesByTbNode(),
                        previous.nodeMetricsByInstance(), previous.nodeMetricsByTbNode(),
                        previous.degraded());
            }
            log.warn("Thanos 수집 상태 조회 실패 — 정상 스냅샷이 없어 상태 미확정으로 표시합니다: {}", e.getMessage());
            return Snapshot.empty(now, true);
        }
    }

    /** 한 행의 지표 이름을 instance 키와(tb 라벨이 있으면) Tumblebug VM 키 양쪽에 기록한다 */
    private static void addNodeMetric(Map<String, Object> row,
                                      String metricName,
                                      Map<String, Set<String>> byInstance,
                                      Map<String, Set<String>> byTbNode) {
        String instance = label(row, "instance");
        if (instance != null && !instance.isBlank()) {
            byInstance.computeIfAbsent(instance, k -> new HashSet<>()).add(metricName);
        }
        String tbKey = tbKey(row);
        if (tbKey != null) {
            byTbNode.computeIfAbsent(tbKey, k -> new HashSet<>()).add(metricName);
        }
    }

    /** Duration → PromQL 표기 (10m / 24h / 30d) */
    static String promDuration(Duration d) {
        if (d.toMinutes() < 60) {
            return d.toMinutes() + "m";
        }
        if (d.toHours() < 48) {
            return d.toHours() + "h";
        }
        return d.toDays() + "d";
    }

    private static String tbKey(Map<String, Object> row) {
        String ns = label(row, "tb_ns");
        String infra = label(row, "tb_infra");
        String node = label(row, "tb_node");
        if (ns == null || infra == null || node == null || ns.isBlank() || infra.isBlank() || node.isBlank()) {
            return null;
        }
        return Snapshot.tbKey(ns, infra, node);
    }

    private static int compareGpu(GpuDevice a, GpuDevice b) {
        try {
            return Integer.compare(Integer.parseInt(a.index()), Integer.parseInt(b.index()));
        } catch (NumberFormatException e) {
            return String.valueOf(a.index()).compareTo(String.valueOf(b.index()));
        }
    }

    private static boolean isPositive(String raw) {
        if (raw == null) {
            return false;
        }
        try {
            return Double.parseDouble(raw) > 0;
        } catch (NumberFormatException e) {
            return false;
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
