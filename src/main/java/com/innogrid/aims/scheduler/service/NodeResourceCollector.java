package com.innogrid.aims.scheduler.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.model.ExcludedNode;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.NodeResource.PowerSource;

/**
 * Thanos/Prometheus에서 실시간 노드 자원 상태를 수집한다.
 * 스케줄링 엔진에 NodeResource 목록을 제공한다.
 *
 * <p><b>핵심 메트릭(CPU 사용률·코어 수·MemTotal·MemAvailable)을 알 수 없는 노드는 수집하지
 * 않는다.</b> 질의가 실패하거나 시리즈가 없을 때 0 으로 채우면 그 노드는 "CPU 0% 의 빈 노드"로
 * 보인다 — 점수가 치솟아 배치가 몰리고, 통합 진단은 가동 중인 노드를 반납 후보로 판정한다.
 * 코어 수가 0 이면 {@code Math.max(…, 1)} 로 1코어짜리 가짜 노드가 만들어지기도 했다.
 * 모르는 노드는 후보가 아니다. 제외 사실은 WARN 로그로 남긴다.</p>
 *
 * <p>디스크·네트워크·전력·온도는 없으면 0 으로 둔다. 전력·온도는 {@link PowerSource} 플래그가
 * "수집 안 됨"을 표시하고, 디스크·네트워크는 배치 결정에서 비중이 작다.</p>
 */
@Service
public class NodeResourceCollector {

    private static final Logger log = LoggerFactory.getLogger(NodeResourceCollector.class);

    private final VictoriaMetricsClient vmClient;
    private final SchedulerProperties properties;
    private final PowerModel powerModel;

    public NodeResourceCollector(VictoriaMetricsClient vmClient,
                                 SchedulerProperties properties,
                                 PowerModel powerModel) {
        this.vmClient = vmClient;
        this.properties = properties;
        this.powerModel = powerModel;
    }

    /**
     * 수집 결과 — 후보 노드와, 핵심 메트릭을 알 수 없어 뺀 노드.
     *
     * <p>뺀 노드를 함께 돌려주는 이유는 진단 때문이다. 로그에만 남기면 클러스터 진단의 노드 수가
     * 조용히 줄어들어 "10대 중 1대 제외"가 "9대 클러스터"로 보인다.</p>
     */
    public record Snapshot(List<NodeResource> nodes, List<ExcludedNode> excluded) {
        public Snapshot {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            excluded = excluded == null ? List.of() : List.copyOf(excluded);
        }
    }

    /**
     * 모든 활성 노드의 실시간 자원 상태를 수집한다.
     * 핵심 메트릭을 알 수 없는 노드는 목록에 들어가지 않는다 — 제외 목록은 {@link #collect()} 가 준다.
     */
    public List<NodeResource> collectAll() {
        return collect().nodes();
    }

    /** 후보 노드와 제외 노드를 함께 수집한다. */
    @SuppressWarnings("unchecked")
    public Snapshot collect() {
        List<NodeResource> nodes = new ArrayList<>();
        List<ExcludedNode> excluded = new ArrayList<>();

        try {
            // up == 1인 노드만 수집
            Map<String, Object> upResponse = vmClient.query(
                    String.format("up{job=\"%s\"} == 1", properties.getNodeJob()));
            if (upResponse == null) return new Snapshot(nodes, excluded);

            Map<String, Object> data = (Map<String, Object>) upResponse.get("data");
            if (data == null) return new Snapshot(nodes, excluded);

            List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");
            if (results == null) return new Snapshot(nodes, excluded);

            // GPU 개수는 노드마다 조회하지 않고 한 번에 받아 둔다.
            Map<String, Integer> gpuCounts = collectGpuCounts();

            for (Map<String, Object> result : results) {
                Map<String, String> labels = (Map<String, String>) result.get("metric");
                String instance = labels.getOrDefault("instance", "unknown");
                String mdcId = labels.getOrDefault("mdc_id", "unknown");

                try {
                    NodeResource node = collectNodeMetrics(mdcId, instance,
                            gpuCounts.getOrDefault(instance, 0), excluded);
                    if (node != null) {
                        nodes.add(node);
                    }
                } catch (Exception e) {
                    log.debug("노드 메트릭 수집 실패: instance={}, error={}", instance, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("노드 자원 수집 실패: {}", e.getMessage());
        }

        return new Snapshot(nodes, excluded);
    }

    /**
     * 노드 한 대의 메트릭. 핵심 메트릭 4종 중 하나라도 알 수 없으면 {@code excluded} 에 기록하고 {@code null}.
     */
    private NodeResource collectNodeMetrics(String mdcId, String instance, int totalGpu,
                                            List<ExcludedNode> excluded) {
        String filter = String.format("{instance=\"%s\"}", instance);
        String mount = properties.getDiskMountPoint();

        // ── 핵심 메트릭: 없으면 노드 자체를 수집하지 않는다 ─────────
        OptionalDouble cpuUsage = requiredQuery(String.format(
                "100 - (avg(irate(node_cpu_seconds_total{mode=\"idle\",instance=\"%s\"}[5m])) * 100)", instance));
        OptionalDouble cores = requiredQuery(String.format(
                "count(node_cpu_seconds_total{mode=\"idle\",instance=\"%s\"})", instance));
        OptionalDouble memTotal = requiredQuery("node_memory_MemTotal_bytes" + filter);
        OptionalDouble memAvail = requiredQuery("node_memory_MemAvailable_bytes" + filter);

        List<String> missing = new ArrayList<>();
        if (cpuUsage.isEmpty()) missing.add("cpu사용률");
        if (cores.isEmpty() || cores.getAsDouble() <= 0) missing.add("코어수");
        if (memTotal.isEmpty() || memTotal.getAsDouble() <= 0) missing.add("MemTotal");
        if (memAvail.isEmpty()) missing.add("MemAvailable");
        if (!missing.isEmpty()) {
            log.warn("노드 수집 제외: instance={} — 판단 불가 메트릭 {} (질의 실패 또는 시리즈 없음). "
                    + "0 으로 채우면 빈 노드로 오판하므로 후보에서 뺀다", instance, missing);
            excluded.add(new ExcludedNode(mdcId, instance, missing));
            return null;
        }

        double memUsage = (1 - memAvail.getAsDouble() / memTotal.getAsDouble()) * 100;
        int totalCpu = (int) cores.getAsDouble();
        long totalMemMb = (long) (memTotal.getAsDouble() / 1024 / 1024);

        // ── 부가 메트릭: 없으면 0 ────────────────────────────────
        // 마운트 포인트를 고정하지 않으면 여러 시리즈 중 첫 건이 잡혀 값이 흔들린다.
        double diskUsage = safeQuery(String.format(
                "(1 - (node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay\",mountpoint=\"%s\",instance=\"%s\"}"
                        + " / node_filesystem_size_bytes{mountpoint=\"%s\",instance=\"%s\"})) * 100",
                mount, instance, mount, instance));

        double powerWatts = safeQuery("ipmi_dcmi_power_consumption_watts" + filter);
        // 괄호를 닫지 않으면 PromQL 파싱 오류가 나고 값이 항상 0이 된다.
        double temperature = safeQuery(String.format(
                "avg(ipmi_temperature_celsius{name=~\"CPU.*\",instance=\"%s\"})", instance));
        double networkRx = safeQuery("sum(irate(node_network_receive_bytes_total" + filter + "[5m]))");
        double networkTx = safeQuery("sum(irate(node_network_transmit_bytes_total" + filter + "[5m]))");

        NodeResource node = new NodeResource(mdcId, instance,
                totalCpu, totalMemMb, totalGpu,
                cpuUsage.getAsDouble(), memUsage, diskUsage,
                powerWatts, powerModel.capacityOf(instance), temperature,
                networkRx, networkTx);

        // IPMI 값이 하나도 없으면 전력·온도 판단 근거가 없다는 뜻이다.
        node.setPowerSource(powerWatts > 0 || temperature > 0 ? PowerSource.IPMI : PowerSource.NONE);
        return node;
    }

    /**
     * 노드별 GPU 개수. DCGM exporter 가 없으면 빈 결과가 되고 GPU 요구 워크로드는 배치되지 않는다.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Integer> collectGpuCounts() {
        Map<String, Integer> counts = new HashMap<>();
        try {
            for (Map<String, Object> result : vmClient.queryResults("count by(instance) (DCGM_FI_DEV_GPU_UTIL)")) {
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
     * 핵심 메트릭 질의 — "값이 없다"와 "값이 0 이다"를 구분한다.
     *
     * <p>{@code queryScalar} 는 질의 실패도 시리즈 없음도 0.0 으로 돌려주므로 구분할 수 없다.
     * 결과 벡터를 직접 받아 비어 있으면 {@code empty}, 예외도 {@code empty} 로 돌려준다.</p>
     */
    @SuppressWarnings("unchecked")
    private OptionalDouble requiredQuery(String promQL) {
        try {
            List<Map<String, Object>> results = vmClient.queryResults(promQL);
            if (results == null || results.isEmpty()) return OptionalDouble.empty();

            List<Object> value = (List<Object>) results.get(0).get("value");
            if (value == null || value.size() < 2) return OptionalDouble.empty();

            double parsed = Double.parseDouble(value.get(1).toString());
            return Double.isNaN(parsed) ? OptionalDouble.empty() : OptionalDouble.of(parsed);
        } catch (Exception e) {
            log.debug("핵심 메트릭 조회 실패: query={}, error={}", promQL, e.getMessage());
            return OptionalDouble.empty();
        }
    }

    /** 부가 메트릭 질의 — 없거나 실패하면 0. */
    private double safeQuery(String promQL) {
        try {
            return vmClient.queryScalar(promQL);
        } catch (Exception e) {
            log.debug("메트릭 조회 실패: query={}, error={}", promQL, e.getMessage());
            return 0;
        }
    }
}
