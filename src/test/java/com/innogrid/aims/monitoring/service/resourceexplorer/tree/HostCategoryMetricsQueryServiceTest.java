package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.cpu.VmCpuMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.memory.VmMemoryMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.network.VmNetworkMetric;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostCategoryMetricsQueryService.CpuDevice;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostCategoryMetricsQueryService.CpuInventory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 항목 패널 Thanos 조회 — tb_* 라벨 PromQL, CPU KPI·목록(cpu.info 유무), 시계열 파싱, 장애 처리. */
class HostCategoryMetricsQueryServiceTest {

    private static final MetricSelector SEL = MetricSelector.tumblebugNode("openstack", "openstack-infra-01", "vm-1");
    private static final String LABELS = "tb_ns=\"openstack\",tb_infra=\"openstack-infra-01\",tb_node=\"vm-1\"";

    VictoriaMetricsClient client = mock(VictoriaMetricsClient.class);
    HostCategoryMetricsQueryService service = new HostCategoryMetricsQueryService(client);

    @Test
    void CPU_KPI는_mode별_rate_비율을_tb_라벨로_조회한다() {
        when(client.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            assertThat(q).contains(LABELS);
            if (q.startsWith("100 - ")) return vector("32.46");
            if (q.contains("mode=\"iowait\"")) return vector("1.24");
            if (q.contains("mode=\"system\"")) return vector("5.55");
            return List.of();                                       // steal 미수집
        });

        CpuSummaryResponse res = service.cpuSummary(SEL);

        assertThat(res.usedPercent()).isEqualTo(32.5);
        assertThat(res.ioWaitPercent()).isEqualTo(1.2);
        assertThat(res.systemPercent()).isEqualTo(5.6);
        assertThat(res.stealPercent()).isNull();
    }

    @Test
    void CPU_목록은_node_cpu_info를_소켓별로_묶는다() {
        when(client.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.startsWith("node_cpu_info")) {
                return List.of(
                        cpuInfo("0", "0", "0", "GenuineIntel", "Xeon Gold 6338"),
                        cpuInfo("0", "0", "1", "GenuineIntel", "Xeon Gold 6338"),   // 같은 코어의 HT 스레드
                        cpuInfo("0", "1", "2", "GenuineIntel", "Xeon Gold 6338"),
                        cpuInfo("1", "0", "3", "GenuineIntel", "Xeon Gold 6338"));
            }
            return List.of();
        });

        List<CpuDevice> devices = service.cpuDevices(SEL);

        assertThat(devices).hasSize(2);
        assertThat(devices.get(0)).isEqualTo(new CpuDevice(0, "GenuineIntel", "Xeon Gold 6338", 2, 3));
        assertThat(devices.get(1)).isEqualTo(new CpuDevice(1, "GenuineIntel", "Xeon Gold 6338", 1, 1));
    }

    @Test
    void cpu_info가_없으면_논리_CPU_수로_한_행을_만들고_그것도_없으면_빈_목록이다() {
        when(client.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            return q.startsWith("count by (instance) (node_cpu_seconds_total") ? vector("2") : List.of();
        });
        assertThat(service.cpuDevices(SEL)).containsExactly(new CpuDevice(0, null, null, 2, 2));

        when(client.queryResults(anyString())).thenReturn(List.of());
        assertThat(service.cpuDevices(SEL)).isEmpty();
    }

    @Test
    void CPU_인벤토리는_목록을_만든_표본의_시각을_함께_돌려준다() {
        when(client.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            return q.startsWith("node_cpu_info")
                    ? List.of(cpuInfo("0", "0", "0", "GenuineIntel", "Xeon Gold 6338"))
                    : List.of();
        });
        CpuInventory fromInfo = service.cpuInventory(SEL);
        assertThat(fromInfo.devices()).containsExactly(new CpuDevice(0, "GenuineIntel", "Xeon Gold 6338", 1, 1));
        assertThat(fromInfo.collectedAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));

        when(client.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            return q.startsWith("count by (instance) (node_cpu_seconds_total") ? vector("8") : List.of();
        });
        CpuInventory fromCount = service.cpuInventory(SEL);
        assertThat(fromCount.devices()).containsExactly(new CpuDevice(0, null, null, 8, 8));
        assertThat(fromCount.collectedAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));

        when(client.queryResults(anyString())).thenReturn(List.of());
        assertThat(service.cpuInventory(SEL)).isEqualTo(CpuInventory.EMPTY);
    }

    @Test
    void 노드_CPU_지표는_VM과_같은_mode_PromQL을_쓴다() {
        assertThat(HostCategoryMetricsQueryService.cpuQuery(CpuMetric.CPU_USED, SEL, "5m"))
                .isEqualTo(HostCategoryMetricsQueryService.cpuQuery(VmCpuMetric.CPU_USED, SEL, "5m"));
        assertThat(HostCategoryMetricsQueryService.cpuQuery(CpuMetric.CPU_SOFT_IRQ, SEL, "1h"))
                .isEqualTo("avg by (instance) (rate(node_cpu_seconds_total{mode=\"softirq\"," + LABELS + "}[1h])) * 100");

        when(client.queryRangeResults(anyString(), any(), any(), anyString()))
                .thenReturn(List.of(range(List.of(List.of(1_700_000_000L, "3.33")))));
        assertThat(service.cpuTrend(CpuMetric.CPU_STEAL, SEL, Instant.EPOCH, Instant.EPOCH, "30s"))
                .containsExactly(new DataPoint(1_700_000_000L, 3.3));
    }

    @Test
    void CPU_시계열은_rate_창을_step에_맞추고_점을_소수1자리로_반올림한다() {
        Instant from = Instant.ofEpochSecond(1_700_000_000L);
        Instant to = Instant.ofEpochSecond(1_700_003_600L);
        when(client.queryRangeResults(anyString(), any(), any(), anyString()))
                .thenReturn(List.of(range(List.of(List.of(1_700_000_000L, "12.34"), List.of(1_700_000_030L, "NaN"),
                        List.of(1_700_000_060L, "15.0")))));

        List<DataPoint> points = service.cpuTrend(VmCpuMetric.CPU_STEAL, SEL, from, to, "30s");

        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        verify(client).queryRangeResults(q.capture(), eq(from), eq(to), eq("30s"));
        assertThat(q.getValue()).isEqualTo(
                "avg by (instance) (rate(node_cpu_seconds_total{mode=\"steal\"," + LABELS + "}[5m])) * 100");
        assertThat(points).containsExactly(new DataPoint(1_700_000_000L, 12.3), new DataPoint(1_700_000_060L, 15.0));
    }

    @Test
    void 메모리_네트워크_PromQL은_지표별_메트릭과_필터를_쓴다() {
        assertThat(HostCategoryMetricsQueryService.memoryQuery(VmMemoryMetric.MEMORY_USED, SEL))
                .isEqualTo("(node_memory_MemTotal_bytes{" + LABELS + "} - node_memory_MemAvailable_bytes{" + LABELS + "}) / 1073741824");
        assertThat(HostCategoryMetricsQueryService.memoryQuery(VmMemoryMetric.MEMORY_AVAILABLE_PERCENT, SEL))
                .isEqualTo("node_memory_MemAvailable_bytes{" + LABELS + "} / node_memory_MemTotal_bytes{" + LABELS + "} * 100");
        assertThat(HostCategoryMetricsQueryService.memoryQuery(VmMemoryMetric.MEMORY_SHARED, SEL))
                .isEqualTo("node_memory_Shmem_bytes{" + LABELS + "} / 1073741824");
        assertThat(HostCategoryMetricsQueryService.networkQuery(VmNetworkMetric.NETWORK_DROP_OUT, SEL, "1h"))
                .isEqualTo("sum by (instance) (rate(node_network_transmit_drop_total{device!~\"lo|veth.*|docker.*|br.*|virbr.*\","
                        + LABELS + "}[1h]))");
        assertThat(HostCategoryMetricsQueryService.cpuQuery(VmCpuMetric.CPU_USED, SEL, "5m"))
                .startsWith("100 - avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\",");
    }

    @Test
    void Thanos_장애나_결과_부재는_미수집으로_처리한다() {
        when(client.queryResults(anyString())).thenThrow(new RuntimeException("connection refused"));
        when(client.queryRangeResults(anyString(), any(), any(), anyString())).thenThrow(new RuntimeException("timeout"));

        assertThat(service.cpuSummary(SEL)).isEqualTo(new CpuSummaryResponse(null, null, null, null));
        assertThat(service.cpuDevices(SEL)).isEmpty();
        assertThat(service.memoryTrend(VmMemoryMetric.MEMORY_TOTAL, SEL, Instant.EPOCH, Instant.EPOCH, "5m")).isEmpty();

        doReturn(List.of()).when(client).queryRangeResults(anyString(), any(), any(), anyString());   // 예외 스텁 뒤 재스텁
        assertThat(service.networkTrend(VmNetworkMetric.NETWORK_RECV, SEL, Instant.EPOCH, Instant.EPOCH, "5m")).isEmpty();
    }

    // ── 대역 ────────────────────────────────────────────────────

    static List<Map<String, Object>> vector(String value) {
        return List.of(Map.of("metric", Map.of(), "value", List.of(1_700_000_000L, value)));
    }

    static Map<String, Object> range(List<List<Object>> values) {
        return Map.of("metric", Map.of(), "values", values);
    }

    static Map<String, Object> cpuInfo(String pkg, String core, String cpu, String vendor, String model) {
        return Map.of("metric", Map.of("package", pkg, "core", core, "cpu", cpu, "vendor", vendor, "model_name", model),
                "value", List.of(1_700_000_000L, "1"));
    }
}
