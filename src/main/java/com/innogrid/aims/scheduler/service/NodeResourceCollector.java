package com.innogrid.aims.scheduler.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.NodeResource.PowerSource;

/**
 * Thanos/Prometheus에서 실시간 노드 자원 상태를 수집한다.
 * 스케줄링 엔진에 NodeResource 목록을 제공한다.
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
     * 모든 활성 노드의 실시간 자원 상태를 수집한다.
     */
    @SuppressWarnings("unchecked")
    public List<NodeResource> collectAll() {
        List<NodeResource> nodes = new ArrayList<>();

        try {
            // up == 1인 노드만 수집
            Map<String, Object> upResponse = vmClient.query(
                    String.format("up{job=\"%s\"} == 1", properties.getNodeJob()));
            if (upResponse == null) return nodes;

            Map<String, Object> data = (Map<String, Object>) upResponse.get("data");
            if (data == null) return nodes;

            List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");
            if (results == null) return nodes;

            // GPU 개수는 노드마다 조회하지 않고 한 번에 받아 둔다.
            Map<String, Integer> gpuCounts = collectGpuCounts();

            for (Map<String, Object> result : results) {
                Map<String, String> labels = (Map<String, String>) result.get("metric");
                String instance = labels.getOrDefault("instance", "unknown");
                String mdcId = labels.getOrDefault("mdc_id", "unknown");

                try {
                    NodeResource node = collectNodeMetrics(mdcId, instance,
                            gpuCounts.getOrDefault(instance, 0));
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

        return nodes;
    }

    private NodeResource collectNodeMetrics(String mdcId, String instance, int totalGpu) {
        String filter = String.format("{instance=\"%s\"}", instance);
        String mount = properties.getDiskMountPoint();

        double cpuUsage = safeQuery(String.format(
                "100 - (avg(irate(node_cpu_seconds_total{mode=\"idle\",instance=\"%s\"}[5m])) * 100)", instance));
        double memTotal = safeQuery("node_memory_MemTotal_bytes" + filter);
        double memAvail = safeQuery("node_memory_MemAvailable_bytes" + filter);
        double memUsage = memTotal > 0 ? (1 - memAvail / memTotal) * 100 : 0;

        // 마운트 포인트를 고정하지 않으면 여러 시리즈 중 첫 건이 잡혀 값이 흔들린다.
        double diskUsage = safeQuery(String.format(
                "(1 - (node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay\",mountpoint=\"%s\",instance=\"%s\"}"
                        + " / node_filesystem_size_bytes{mountpoint=\"%s\",instance=\"%s\"})) * 100",
                mount, instance, mount, instance));

        int totalCpu = (int) safeQuery(String.format(
                "count(node_cpu_seconds_total{mode=\"idle\",instance=\"%s\"})", instance));
        long totalMemMb = (long) (memTotal / 1024 / 1024);

        double powerWatts = safeQuery("ipmi_dcmi_power_consumption_watts" + filter);
        // 괄호를 닫지 않으면 PromQL 파싱 오류가 나고 값이 항상 0이 된다.
        double temperature = safeQuery(String.format(
                "avg(ipmi_temperature_celsius{name=~\"CPU.*\",instance=\"%s\"})", instance));
        double networkRx = safeQuery("sum(irate(node_network_receive_bytes_total" + filter + "[5m]))");
        double networkTx = safeQuery("sum(irate(node_network_transmit_bytes_total" + filter + "[5m]))");

        NodeResource node = new NodeResource(mdcId, instance,
                Math.max(totalCpu, 1), Math.max(totalMemMb, 1024), totalGpu,
                cpuUsage, memUsage, diskUsage,
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

    private double safeQuery(String promQL) {
        try {
            return vmClient.queryScalar(promQL);
        } catch (Exception e) {
            log.debug("메트릭 조회 실패: query={}, error={}", promQL, e.getMessage());
            return 0;
        }
    }
}
