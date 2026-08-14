package com.innogrid.aims.scheduler.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.scheduler.model.NodeResource;

/**
 * Thanos/Prometheus에서 실시간 노드 자원 상태를 수집한다.
 * 스케줄링 엔진에 NodeResource 목록을 제공한다.
 */
@Service
public class NodeResourceCollector {

    private static final Logger log = LoggerFactory.getLogger(NodeResourceCollector.class);

    private final VictoriaMetricsClient vmClient;

    public NodeResourceCollector(VictoriaMetricsClient vmClient) {
        this.vmClient = vmClient;
    }

    /**
     * 모든 활성 노드의 실시간 자원 상태를 수집한다.
     */
    @SuppressWarnings("unchecked")
    public List<NodeResource> collectAll() {
        List<NodeResource> nodes = new ArrayList<>();

        try {
            // up == 1인 노드만 수집
            Map<String, Object> upResponse = vmClient.query("up{job=\"mdc-nodes\"} == 1");
            if (upResponse == null) return nodes;

            Map<String, Object> data = (Map<String, Object>) upResponse.get("data");
            if (data == null) return nodes;

            List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");
            if (results == null) return nodes;

            for (Map<String, Object> result : results) {
                Map<String, String> labels = (Map<String, String>) result.get("metric");
                String instance = labels.getOrDefault("instance", "unknown");
                String mdcId = labels.getOrDefault("mdc_id", "unknown");

                try {
                    NodeResource node = collectNodeMetrics(mdcId, instance);
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

    private NodeResource collectNodeMetrics(String mdcId, String instance) {
        String filter = String.format("{instance=\"%s\"}", instance);

        double cpuUsage = safeQuery("100 - (avg(irate(node_cpu_seconds_total{mode=\"idle\",instance=\"" + instance + "\"}[5m])) * 100)");
        double memTotal = safeQuery("node_memory_MemTotal_bytes" + filter);
        double memAvail = safeQuery("node_memory_MemAvailable_bytes" + filter);
        double memUsage = memTotal > 0 ? (1 - memAvail / memTotal) * 100 : 0;
        double diskUsage = safeQuery("(1 - (node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay\",instance=\"" + instance + "\"} / node_filesystem_size_bytes)) * 100");

        int totalCpu = (int) safeQuery("count(node_cpu_seconds_total{mode=\"idle\",instance=\"" + instance + "\"})");
        long totalMemMb = (long) (memTotal / 1024 / 1024);

        double powerWatts = safeQuery("ipmi_dcmi_power_consumption_watts" + filter);
        double temperature = safeQuery("avg(ipmi_temperature_celsius{name=~\"CPU.*\"," + filter.substring(1));
        double networkRx = safeQuery("sum(irate(node_network_receive_bytes_total" + filter + "[5m]))");
        double networkTx = safeQuery("sum(irate(node_network_transmit_bytes_total" + filter + "[5m]))");

        return new NodeResource(mdcId, instance,
                Math.max(totalCpu, 1), Math.max(totalMemMb, 1024), 0,
                cpuUsage, memUsage, diskUsage,
                powerWatts, powerWatts > 0 ? powerWatts * 1.5 : 500, temperature,
                networkRx, networkTx);
    }

    private double safeQuery(String promQL) {
        try {
            return vmClient.queryScalar(promQL);
        } catch (Exception e) {
            return 0;
        }
    }
}
