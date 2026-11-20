package com.innogrid.aims.scheduler.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.entity.NodeExporterMapping;
import com.innogrid.aims.scheduler.metrics.SchedulerMetricsGateway;
import com.innogrid.aims.scheduler.model.ExporterInstanceCandidates;
import com.innogrid.aims.scheduler.model.ExporterInstanceCandidates.Assignment;
import com.innogrid.aims.scheduler.repository.NodeExporterMappingRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 후보 발견 — 세 질의가 각각 실패할 수 있고, 실패한 질의만 {@code unavailable}에 남으며 나머지 결과는 그대로 나온다.
 * 수집기용 발견({@code discoverGpuCounts}·{@code discoverIpmiInstances})은 실패를 빈 집합으로 삼키는데, 후보 API는 그러면
 * "없다"와 "못 받았다"가 섞이므로 따로 구분한다. 시리즈 수를 못 읽는 행은 그 행만 건너뛴다.
 */
class ExporterInstanceResolverTest {

    private static Map<String, Object> row(String instance, String value) {
        return Map.of("metric", Map.of("instance", instance), "value", List.of(1L, value));
    }

    private final SchedulerProperties props = new SchedulerProperties();
    private final MetricsQueryGuard guard = new MetricsQueryGuard(4);

    private String nodeQuery(SchedulerProperties p) {
        return String.format(ExporterInstanceResolver.NODE_DISCOVERY, p.getNodeJob());
    }

    @DisplayName("세 질의가 답하면 unavailable 은 비고, DCGM 시리즈 수가 gpuCount 로, 노드 목록이 배정의 기준이 된다")
    @Test
    void all_three_queries_answer() {
        SchedulerMetricsGateway gw = mock(SchedulerMetricsGateway.class);
        when(gw.queryResults(eq(ExporterInstanceResolver.DCGM_DISCOVERY))).thenReturn(List.of(row("gpu-1:9400", "4")));
        when(gw.queryResults(eq(ExporterInstanceResolver.IPMI_DISCOVERY))).thenReturn(List.of(row("ipmi-exporter:9290", "1")));
        when(gw.queryResults(eq(nodeQuery(props)))).thenReturn(List.of(row("gpu-1:9100", "1"), row("cpu-1:9100", "1")));
        NodeExporterMappingRepository ledger = mock(NodeExporterMappingRepository.class);
        NodeExporterMapping m = new NodeExporterMapping("cpu-1:9100");
        m.setIpmiInstance("ipmi-exporter:9290");
        when(ledger.findAll()).thenReturn(List.of(m));

        ExporterInstanceCandidates out = new ExporterInstanceResolver(ledger, gw, guard, props).candidates();

        assertTrue(out.unavailable().isEmpty());
        assertEquals(List.of("cpu-1:9100", "gpu-1:9100"), out.nodeInstances());
        assertEquals(1, out.dcgm().size());
        assertEquals(4, out.dcgm().get(0).gpuCount());
        assertEquals(Assignment.HOSTNAME, out.dcgm().get(0).assignedBy());
        assertEquals("gpu-1:9100", out.dcgm().get(0).assignedNodeId());
        assertEquals(Assignment.LEDGER, out.ipmi().get(0).assignedBy());
        assertEquals("cpu-1:9100", out.ipmi().get(0).assignedNodeId());
        assertEquals(List.of("cpu-1:9100"), out.ipmi().get(0).ledgerNodeIds());
    }

    @DisplayName("한 질의가 예외를 내면 그 이름만 unavailable 에 남고 다른 두 결과는 그대로다")
    @Test
    void one_failing_query_is_named_and_the_rest_survive() {
        SchedulerMetricsGateway gw = mock(SchedulerMetricsGateway.class);
        when(gw.queryResults(eq(ExporterInstanceResolver.DCGM_DISCOVERY))).thenThrow(new IllegalStateException("저장소 500"));
        when(gw.queryResults(eq(ExporterInstanceResolver.IPMI_DISCOVERY))).thenReturn(List.of(row("n1:9290", "1")));
        when(gw.queryResults(eq(nodeQuery(props)))).thenReturn(List.of(row("n1:9100", "1")));

        ExporterInstanceCandidates out = new ExporterInstanceResolver(gw, guard, props).candidates();

        assertEquals(List.of("dcgm"), out.unavailable());
        assertTrue(out.dcgm().isEmpty(), "못 받은 목록은 비어 있되 unavailable 이 그 뜻을 말한다");
        assertEquals(Assignment.HOSTNAME, out.ipmi().get(0).assignedBy());
        assertEquals(List.of("n1:9100"), out.nodeInstances());
    }

    @DisplayName("상한 안에 답하지 않은 질의도 unavailable 이다 — 수집기용 발견처럼 빈 집합으로 삼키지 않는다")
    @Test
    void timeout_counts_as_unavailable() throws Exception {
        SchedulerProperties slow = new SchedulerProperties();
        slow.getMetrics().setQueryTimeoutMs(300);
        CountDownLatch release = new CountDownLatch(1);
        SchedulerMetricsGateway gw = mock(SchedulerMetricsGateway.class);
        when(gw.queryResults(anyString())).thenAnswer(inv -> {
            if (ExporterInstanceResolver.IPMI_DISCOVERY.equals(inv.getArgument(0))) {
                release.await();   // 테스트가 풀어 줄 때까지 답하지 않는다 — 시간이 아니라 래치로 결정한다
            }
            return List.of(row("n1:9100", "1"));
        });

        try {
            ExporterInstanceCandidates out = new ExporterInstanceResolver(gw, guard, slow).candidates();

            assertEquals(List.of("ipmi"), out.unavailable());
            assertTrue(out.ipmi().isEmpty(), "빈 목록 자체는 '없음' 과 같아 보인다 — 구분은 unavailable 이 한다");
            assertEquals(1, out.dcgm().size(), "같은 상한 안에 답한 다른 질의는 그대로");
            assertEquals(List.of("n1:9100"), out.nodeInstances());
        } finally {
            release.countDown();
        }
    }

    @DisplayName("노드 목록 질의만 실패하면 후보는 남되 배정은 전부 UNKNOWN 이고 ledgerOnly 는 그대로 대조된다")
    @Test
    void nodes_query_failure_makes_assignment_unknown() {
        SchedulerMetricsGateway gw = mock(SchedulerMetricsGateway.class);
        when(gw.queryResults(eq(ExporterInstanceResolver.DCGM_DISCOVERY))).thenReturn(List.of(row("n1:9400", "2")));
        when(gw.queryResults(eq(ExporterInstanceResolver.IPMI_DISCOVERY))).thenReturn(List.of());
        when(gw.queryResults(eq(nodeQuery(props)))).thenThrow(new IllegalStateException("up 질의 실패"));
        NodeExporterMappingRepository ledger = mock(NodeExporterMappingRepository.class);
        NodeExporterMapping m = new NodeExporterMapping("n1:9100");
        m.setDcgmInstance("n1:9400");
        m.setIpmiInstance("typo:9290");
        when(ledger.findAll()).thenReturn(List.of(m));

        ExporterInstanceCandidates out = new ExporterInstanceResolver(ledger, gw, guard, props).candidates();

        assertEquals(List.of("nodes"), out.unavailable());
        assertEquals(Assignment.UNKNOWN, out.dcgm().get(0).assignedBy());
        assertNull(out.dcgm().get(0).assignedNodeId());
        assertEquals(List.of("n1:9100"), out.dcgm().get(0).ledgerNodeIds());
        assertEquals(List.of("typo:9290"), out.ledgerOnlyIpmi(), "IPMI 는 답했으니(빈 목록) 원장값 대조는 유효하다");
        assertTrue(out.ledgerOnlyDcgm().isEmpty());
    }

    @DisplayName("시리즈 수를 못 읽는 행(비수치·NaN·값 없음)은 그 행만 건너뛰고 질의는 성공으로 남는다")
    @Test
    void unreadable_series_count_rows_are_skipped_individually() {
        SchedulerMetricsGateway gw = mock(SchedulerMetricsGateway.class);
        when(gw.queryResults(eq(ExporterInstanceResolver.DCGM_DISCOVERY))).thenReturn(List.of(
                row("ok:9400", "3"), row("text:9400", "abc"), row("nan:9400", "NaN"), row("inf:9400", "+Inf"),
                Map.of("metric", Map.of("instance", "novalue:9400")), row("ok2:9400", "1")));
        when(gw.queryResults(eq(ExporterInstanceResolver.IPMI_DISCOVERY))).thenReturn(List.of());
        when(gw.queryResults(eq(nodeQuery(props)))).thenReturn(List.of());

        ExporterInstanceCandidates out = new ExporterInstanceResolver(gw, guard, props).candidates();

        assertTrue(out.unavailable().isEmpty(), "질의는 답했다 — 행 하나가 이상해도 실패로 보지 않는다");
        assertEquals(List.of("ok2:9400", "ok:9400"), out.dcgm().stream().map(c -> c.instance()).toList());
        assertEquals(3, out.dcgm().get(1).gpuCount());
    }

    @DisplayName("게이트웨이가 없으면 세 질의 전부 unavailable 이고 원장값은 대조할 수 없어 ledgerOnly 도 비며 배정은 UNKNOWN 이다")
    @Test
    void no_gateway_marks_everything_unavailable() {
        NodeExporterMappingRepository ledger = mock(NodeExporterMappingRepository.class);
        NodeExporterMapping m = new NodeExporterMapping("n1:9100");
        m.setDcgmInstance("n1:9400");
        when(ledger.findAll()).thenReturn(List.of(m));

        ExporterInstanceCandidates out = new ExporterInstanceResolver(ledger, null, guard, props).candidates();

        assertEquals(List.of("dcgm", "ipmi", "nodes"), out.unavailable());
        assertTrue(out.ledgerOnlyDcgm().isEmpty(), "DCGM 목록을 못 받았으니 원장값을 오타로 몰지 않는다");
        assertTrue(out.dcgm().isEmpty() && out.ipmi().isEmpty() && out.nodeInstances().isEmpty());
    }
}
