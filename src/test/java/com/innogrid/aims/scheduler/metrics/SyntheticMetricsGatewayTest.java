package com.innogrid.aims.scheduler.metrics;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 스케줄러가 실제로 보내는 PromQL 13종에 가상 클러스터가 어떤 형태로 답하는가. 시각은 고정.
 * 질의 문자열은 NodeResourceCollector·PowerEfficiencyService·PowerOptimizationService 의 것을 그대로 복사했다.
 */
class SyntheticMetricsGatewayTest {

    private static final Instant T = Instant.ofEpochSecond(1_800_000_000L);
    private final SyntheticCluster cluster = SyntheticCluster.load("classpath:scheduler/synthetic-cluster.yml");
    private final SyntheticMetricsGateway gw = new SyntheticMetricsGateway(cluster, () -> T);

    private static double val(Map<String, Object> series) {
        return Double.parseDouble(String.valueOf(((List<?>) series.get("value")).get(1)));
    }

    @DisplayName("up{job} == 1 은 노드 8대를 instance·mdc_id·job 라벨과 값 1 로 돌려주고, 다른 job 이면 비어 있다")
    @Test
    void up_query_lists_nodes() {
        Map<String, Object> env = gw.query("up{job=\"mdc-nodes\"} == 1");
        assertEquals("success", env.get("status"));
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) env.get("data");
        @SuppressWarnings("unchecked") List<Map<String, Object>> result = (List<Map<String, Object>>) data.get("result");
        assertEquals(8, result.size());
        @SuppressWarnings("unchecked") Map<String, String> labels = (Map<String, String>) result.get(0).get("metric");
        assertEquals("seoul-01:9100", labels.get("instance"));
        assertEquals("mdc-seoul", labels.get("mdc_id"));
        assertEquals(1.0, val(result.get(0)));
        assertTrue(gw.queryResults("up{job=\"other\"} == 1").isEmpty());
    }

    @DisplayName("핵심 4종 — CPU 사용률(0~100)·코어 수·MemTotal·MemAvailable 은 노드 1대 결과이고 없는 instance 는 빈 결과")
    @Test
    void core_metrics() {
        String i = "seoul-01:9100";
        double cpu = val(gw.queryResults("100 - (avg(irate(node_cpu_seconds_total{mode=\"idle\",instance=\"" + i + "\"}[5m])) * 100)").get(0));
        assertTrue(cpu >= 0 && cpu <= 100);
        assertEquals(32.0, val(gw.queryResults("count(node_cpu_seconds_total{mode=\"idle\",instance=\"" + i + "\"})").get(0)));
        double total = val(gw.queryResults("node_memory_MemTotal_bytes{instance=\"" + i + "\"}").get(0));
        double avail = val(gw.queryResults("node_memory_MemAvailable_bytes{instance=\"" + i + "\"}").get(0));
        assertEquals(131072.0 * 1024 * 1024, total);
        assertTrue(avail > 0 && avail < total);
        assertTrue(gw.queryResults("node_memory_MemTotal_bytes{instance=\"ghost:9100\"}").isEmpty(), "없는 노드 = 메트릭 없음");
    }

    @DisplayName("부가 6종 — 디스크·전력·온도·네트워크. IPMI 없는 노드는 전력·온도가 빈 결과라 scalar 0 (실환경의 미계측 재현)")
    @Test
    void extra_metrics_and_ipmi_less_node() {
        String i = "seoul-03:9100", noIpmi = "busan-03:9100";
        double size = gw.queryScalar("node_filesystem_size_bytes{fstype!~\"tmpfs|overlay\",mountpoint=\"/\",instance=\"" + i + "\"}");
        double avail = gw.queryScalar("node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay\",mountpoint=\"/\",instance=\"" + i + "\"}");
        assertEquals(500.0 * 1024 * 1024 * 1024, size);
        assertTrue(avail > 0 && avail < size);
        double watts = gw.queryScalar("ipmi_dcmi_power_consumption_watts{instance=\"" + i + "\"}");
        double temp = gw.queryScalar("avg(ipmi_temperature_celsius{name=~\"CPU.*\",instance=\"" + i + "\"})");
        assertTrue(watts >= 150 && watts <= 500, "유휴~정격 사이: " + watts);
        assertTrue(temp >= 62, "뜨거운 노드 기준 온도 이상: " + temp);
        assertTrue(gw.queryScalar("sum(irate(node_network_receive_bytes_total{instance=\"" + i + "\"}[5m]))") > 0);
        assertTrue(gw.queryScalar("sum(irate(node_network_transmit_bytes_total{instance=\"" + i + "\"}[5m]))") > 0);
        assertEquals(0.0, gw.queryScalar("ipmi_dcmi_power_consumption_watts{instance=\"" + noIpmi + "\"}"));
        assertEquals(0.0, gw.queryScalar("avg(ipmi_temperature_celsius{name=~\"CPU.*\",instance=\"" + noIpmi + "\"})"));
        assertTrue(gw.queryResults("ipmi_dcmi_power_consumption_watts{instance=\"" + noIpmi + "\"}").isEmpty());
    }

    @DisplayName("GPU 수는 count by(instance) (DCGM_FI_DEV_GPU_UTIL) 로 GPU 노드 2대만 나온다")
    @Test
    void gpu_counts() {
        List<Map<String, Object>> r = gw.queryResults("count by(instance) (DCGM_FI_DEV_GPU_UTIL)");
        assertEquals(2, r.size());
        assertEquals(4.0 + 2.0, r.stream().mapToDouble(SyntheticMetricsGatewayTest::val).sum());
    }

    @DisplayName("구간 질의 — 노드별 CPU 는 8시리즈, 전력은 IPMI 노드 5시리즈, 단일 instance 는 1시리즈이고 지점 수는 step 을 따른다")
    @Test
    void range_queries() {
        Instant start = T.minusSeconds(3600), end = T;
        List<Map<String, Object>> cpu = gw.queryRangeResults(
                "100 - (avg by (instance) (irate(node_cpu_seconds_total{mode=\"idle\",job=\"mdc-nodes\"}[5m])) * 100)", start, end, "5m");
        assertEquals(8, cpu.size());
        @SuppressWarnings("unchecked") List<List<Object>> values = (List<List<Object>>) cpu.get(0).get("values");
        assertEquals(13, values.size(), "60분/5분 + 1");
        List<Map<String, Object>> power = gw.queryRangeResults("ipmi_dcmi_power_consumption_watts", start, end, "5m");
        assertEquals(5, power.size());
        List<Map<String, Object>> one = gw.queryRangeResults("ipmi_dcmi_power_consumption_watts{instance=\"seoul-01:9100\"}", start, end, "10m");
        assertEquals(1, one.size());
        @SuppressWarnings("unchecked") List<List<Object>> v1 = (List<List<Object>>) one.get(0).get("values");
        assertEquals(7, v1.size());
        assertTrue(gw.queryRangeResults("ipmi_dcmi_power_consumption_watts{instance=\"busan-03:9100\"}", start, end, "5m").isEmpty(), "IPMI 없는 노드는 시리즈 없음");
    }

    @DisplayName("step 표기 — 5m·30s·1h·숫자·이상한 값")
    @Test
    void step_parsing() {
        assertEquals(300, SyntheticMetricsGateway.parseStep("5m"));
        assertEquals(30, SyntheticMetricsGateway.parseStep("30s"));
        assertEquals(3600, SyntheticMetricsGateway.parseStep("1h"));
        assertEquals(15, SyntheticMetricsGateway.parseStep("15"));
        assertEquals(300, SyntheticMetricsGateway.parseStep("weird"));
        assertEquals(300, SyntheticMetricsGateway.parseStep(null));
    }

    @DisplayName("모르는 메트릭·라벨 해석 — 해석 불가는 빈 결과, 라벨은 = 만 잡는다")
    @Test
    void unknown_and_labels() {
        assertTrue(gw.queryResults("some_unknown_metric{instance=\"seoul-01:9100\"}").isEmpty());
        Map<String, String> labels = SyntheticMetricsGateway.labels("x{fstype!~\"tmpfs|overlay\",mountpoint=\"/\",instance=\"a:9100\",name=~\"CPU.*\"}");
        assertEquals(Map.of("mountpoint", "/", "instance", "a:9100"), labels);
        assertEquals("synthetic", gw.source());
    }
}
