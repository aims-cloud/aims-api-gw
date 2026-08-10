package com.innogrid.aims.monitoring.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.*;

/**
 * VictoriaMetrics PromQL 쿼리를 통해 모니터링 데이터를 조회한다.
 * Frontend에 제공할 DTO로 변환하여 반환한다.
 */
@Service
public class MonitoringService {

    private final VictoriaMetricsClient vmClient;

    public MonitoringService(VictoriaMetricsClient vmClient) {
        this.vmClient = vmClient;
    }

    // ── 리소스 사용률 실시간 ─────────────────────────────────────

    public RealtimeUsageResponse getRealtimeUsage(String mdcId) {
        String f = commaFilter(mdcId);
        return new RealtimeUsageResponse(
                vmClient.queryScalar("100 - (avg(irate(node_cpu_seconds_total{mode=\"idle\"" + f + "}[5m])) * 100)"),
                vmClient.queryScalar("(1 - avg(node_memory_MemAvailable_bytes" + braceFilter(mdcId) + " / node_memory_MemTotal_bytes" + braceFilter(mdcId) + ")) * 100"),
                vmClient.queryScalar("(1 - avg(node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay\"" + f + "} / node_filesystem_size_bytes)) * 100"),
                vmClient.queryScalar("sum(irate(node_network_receive_bytes_total" + braceFilter(mdcId) + "[5m]))"),
                vmClient.queryScalar("sum(irate(node_network_transmit_bytes_total" + braceFilter(mdcId) + "[5m]))")
        );
    }

    public List<MetricTrendResponse> getUsageTrend(String mdcId, Instant from, Instant to) {
        String f = commaFilter(mdcId);
        String promQL = "100 - (avg by(instance) (irate(node_cpu_seconds_total{mode=\"idle\"" + f + "}[5m])) * 100)";
        return toTrendResponse("cpu_usage", vmClient.queryRangeResults(promQL, from, to, "60s"));
    }

    // ── GPU ──────────────────────────────────────────────────────

    public GpuStatusResponse getGpuStatus(String mdcId) {
        String f = braceFilter(mdcId);
        return new GpuStatusResponse(
                vmClient.queryScalar("avg(DCGM_FI_DEV_GPU_UTIL" + f + ")"),
                vmClient.queryScalar("sum(DCGM_FI_DEV_FB_USED" + f + ")"),
                vmClient.queryScalar("sum(DCGM_FI_DEV_FB_FREE" + f + ")"),
                vmClient.queryScalar("avg(DCGM_FI_DEV_GPU_TEMP" + f + ")"),
                vmClient.queryScalar("sum(DCGM_FI_DEV_POWER_USAGE" + f + ")")
        );
    }

    public List<MetricTrendResponse> getGpuTrend(String mdcId, Instant from, Instant to) {
        String f = braceFilter(mdcId);
        return toTrendResponse("gpu_utilization",
                vmClient.queryRangeResults("avg by(gpu, Hostname) (DCGM_FI_DEV_GPU_UTIL" + f + ")", from, to, "60s"));
    }

    // ── 물리 서버 (IPMI) ────────────────────────────────────────

    public PhysicalServerResponse getPhysicalServerStatus(String serverId) {
        String f = String.format("{instance=~\"%s.*\"}", serverId);
        double power = vmClient.queryScalar("ipmi_dcmi_power_consumption_watts" + f);

        List<PhysicalServerResponse.SensorReading> temps = extractSensorReadings(
                vmClient.queryRangeResults("ipmi_temperature_celsius" + f,
                        Instant.now().minusSeconds(60), Instant.now(), "60s"), "°C");

        List<PhysicalServerResponse.SensorReading> fans = extractSensorReadings(
                vmClient.queryRangeResults("ipmi_fan_speed_rpm" + f,
                        Instant.now().minusSeconds(60), Instant.now(), "60s"), "RPM");

        return new PhysicalServerResponse(serverId, power, temps, fans);
    }

    public List<MetricTrendResponse> getPowerTrend(String mdcId, Instant from, Instant to) {
        String f = braceFilter(mdcId);
        return toTrendResponse("power_watts",
                vmClient.queryRangeResults("ipmi_dcmi_power_consumption_watts" + f, from, to, "300s"));
    }

    public List<MetricTrendResponse> getTemperatureTrend(String mdcId, Instant from, Instant to) {
        String f = braceFilter(mdcId);
        return toTrendResponse("temperature",
                vmClient.queryRangeResults("avg by(instance) (ipmi_temperature_celsius" + f + ")", from, to, "300s"));
    }

    // ── 노드 성능 ───────────────────────────────────────────────

    public NodeStatusResponse getNodePerformance(String nodeId) {
        String f = String.format("{instance=~\"%s.*\"}", nodeId);
        return new NodeStatusResponse(
                vmClient.queryScalar("node_load1" + f),
                vmClient.queryScalar("node_load5" + f),
                vmClient.queryScalar("node_load15" + f),
                (int) vmClient.queryScalar("node_procs_running" + f),
                vmClient.queryScalar("100 - (avg(irate(node_cpu_seconds_total{mode=\"idle\", instance=~\"" + nodeId + ".*\"}[5m])) * 100)"),
                vmClient.queryScalar("(1 - (node_memory_MemAvailable_bytes" + f + " / node_memory_MemTotal_bytes" + f + ")) * 100"),
                vmClient.queryScalar("(1 - (node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay\", instance=~\"" + nodeId + ".*\"} / node_filesystem_size_bytes)) * 100")
        );
    }

    // ── 헬퍼 ────────────────────────────────────────────────────

    private String commaFilter(String mdcId) {
        return mdcId != null ? String.format(", mdc_id=\"%s\"", mdcId) : "";
    }

    private String braceFilter(String mdcId) {
        return mdcId != null ? String.format("{mdc_id=\"%s\"}", mdcId) : "";
    }

    @SuppressWarnings("unchecked")
    private List<MetricTrendResponse> toTrendResponse(String metricName, List<Map<String, Object>> results) {
        List<MetricTrendResponse> responses = new ArrayList<>();
        for (Map<String, Object> result : results) {
            Map<String, String> labels = (Map<String, String>) result.get("metric");
            String instance = labels.getOrDefault("instance", labels.getOrDefault("Hostname", "unknown"));

            List<List<Object>> values = (List<List<Object>>) result.get("values");
            List<MetricTrendResponse.DataPoint> dataPoints = new ArrayList<>();
            if (values != null) {
                for (List<Object> v : values) {
                    long ts = ((Number) v.get(0)).longValue();
                    double val = Double.parseDouble(v.get(1).toString());
                    dataPoints.add(new MetricTrendResponse.DataPoint(ts, val));
                }
            }
            responses.add(new MetricTrendResponse(metricName, instance, dataPoints));
        }
        return responses;
    }

    @SuppressWarnings("unchecked")
    private List<PhysicalServerResponse.SensorReading> extractSensorReadings(
            List<Map<String, Object>> results, String unit) {
        List<PhysicalServerResponse.SensorReading> readings = new ArrayList<>();
        for (Map<String, Object> result : results) {
            Map<String, String> labels = (Map<String, String>) result.get("metric");
            String name = labels.getOrDefault("name", "unknown");

            List<List<Object>> values = (List<List<Object>>) result.get("values");
            if (values != null && !values.isEmpty()) {
                double val = Double.parseDouble(values.get(values.size() - 1).get(1).toString());
                readings.add(new PhysicalServerResponse.SensorReading(name, val, unit));
            }
        }
        return readings;
    }
}
