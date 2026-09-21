package com.innogrid.aims.scheduler.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PowerOptimizationTrendResponse;
import com.innogrid.aims.scheduler.dto.PowerOptimizationTrendResponse.Point;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.entity.NodeExporterMapping;
import com.innogrid.aims.scheduler.metrics.VictoriaMetricsGateway;
import com.innogrid.aims.scheduler.repository.NodeExporterMappingRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 클러스터 전력 최적화 추세 — 시각별 계산이 현재 진단과 같은 식인가, 결손·미계측·조회 실패를 설계대로 다루는가.
 *
 * <p>합성 시계열: 노드 3대(용량 기본 500W, 정격 유휴 전력 = 500 × 0.3 = 150W), 가동 기준 CPU 5%.
 * 회수 가능은 계측된 유휴 노드라 실측에서 역산한 wakeupPower 합이다({@code (P − Pmax·u)/(1−u)}).
 * t1 — n1 80%/200W · n2 2%/160W(유휴) · n3 1%/150W(유휴) → 가동 1·유휴 2·총 510W·회수 299.53W·PEI 0.40/0.0125/0.0067
 * t2 — n1 70%/200W · n2 60%/210W · n3 1%/150W(유휴)       → 가동 2·유휴 1·회수 146.46W
 * t3 — 3대 모두 가동                                          → 회수 0W</p>
 */
class PowerOptimizationTrendTest {

    private static final long T1 = 1_756_684_800L, T2 = T1 + 300, T3 = T1 + 600;
    private static final double EPS = 1e-4;

    private VictoriaMetricsClient metrics;
    private PowerOptimizationService service;
    private OffsetDateTime from, to;

    @BeforeEach
    void setUp() {
        SchedulerProperties props = new SchedulerProperties();
        metrics = mock(VictoriaMetricsClient.class);
        service = new PowerOptimizationService(mock(SchedulingService.class), new PowerModel(props), props, metrics,
                new MetricsQueryGuard(2));
        from = OffsetDateTime.ofInstant(Instant.ofEpochSecond(T1), ZoneOffset.UTC);
        to = OffsetDateTime.ofInstant(Instant.ofEpochSecond(T3), ZoneOffset.UTC);
    }

    @DisplayName("시각마다 가동 유휴 회수 가능 전력 PEI 를 현재 진단과 같은 식으로 계산한다")
    @Test
    void each_point_computed_like_current() {
        stub("node_cpu_seconds_total", List.of(
                series("n1", T1, 80, T2, 70, T3, 50),
                series("n2", T1, 2, T2, 60, T3, 40),
                series("n3", T1, 1, T2, 1, T3, 30)));
        stub("ipmi_dcmi_power", List.of(
                series("n1", T1, 200, T2, 200, T3, 200),
                series("n2", T1, 160, T2, 210, T3, 200),
                series("n3", T1, 150, T2, 150, T3, 190)));

        PowerOptimizationTrendResponse r = service.trend(from, to, "5m");

        assertNull(r.message());
        assertEquals(3, r.points().size());
        Point p1 = r.points().get(0), p2 = r.points().get(1), p3 = r.points().get(2);
        assertEquals(T1, p1.at().toEpochSecond());
        assertEquals(3, p1.totalNodes()); assertEquals(1, p1.activeNodes()); assertEquals(2, p1.idleNodes());
        assertEquals(510.0, p1.totalPowerWatts(), EPS);
        // 반납분은 wakeupPower — 유휴 2대 다 계측돼 실측에서 역산한다:
        // n2(P=160,u=0.02,Pmax=500): (160-500*0.02)/0.98 = 150/0.98 ≈ 153.0612
        // n3(P=150,u=0.01,Pmax=500): (150-500*0.01)/0.99 = 145/0.99 ≈ 146.4646
        // 합 ≈ 299.5259 → round 299.53 (개별 반올림 후 합산하면 142.86+146.46 처럼 어긋난다 —
        // 서비스는 raw 합을 낸 뒤 한 번만 반올림한다)
        assertEquals(PowerOptimizationService.round((160 - 500 * 0.02) / 0.98 + (150 - 500 * 0.01) / 0.99),
                p1.reclaimableIdleWatts(), EPS,
                "(160-500*0.02)/0.98 + (150-500*0.01)/0.99 ≈ 299.53");
        assertEquals(0.40, p1.maxPeiRaw(), EPS);          // 80/200
        assertEquals(0.0067, p1.minPeiRaw(), 1e-3);       // 1/150
        assertEquals(3, p1.evaluableNodes()); assertTrue(p1.measured());

        // n3(P=150,u=0.01,Pmax=500) 만 유휴: (150-500*0.01)/0.99 = 145/0.99 ≈ 146.4646 → round 146.46
        assertEquals(2, p2.activeNodes());
        assertEquals(PowerOptimizationService.round((150 - 500 * 0.01) / 0.99), p2.reclaimableIdleWatts(), EPS);
        assertEquals(3, p3.activeNodes()); assertEquals(0.0, p3.reclaimableIdleWatts(), EPS);
        assertTrue(p1.reclaimableIdleWatts() > p2.reclaimableIdleWatts() && p2.reclaimableIdleWatts() > p3.reclaimableIdleWatts(),
                "회수 가능 전력이 " + p1.reclaimableIdleWatts() + " → " + p2.reclaimableIdleWatts() + " → 0 으로 줄어든다");
    }

    @DisplayName("전력 메트릭이 없으면 measured false 전력 지표 null 가동 수만 낸다")
    @Test
    void no_power_metric_measured_false() {
        stub("node_cpu_seconds_total", List.of(series("n1", T1, 80, T2, 2), series("n2", T1, 3, T2, 40)));
        stub("ipmi_dcmi_power", List.of());

        PowerOptimizationTrendResponse r = service.trend(from, to, "5m");

        assertEquals(2, r.points().size());
        Point p = r.points().get(0);
        assertFalse(p.measured());
        assertNull(p.totalPowerWatts()); assertNull(p.reclaimableIdleWatts()); assertNull(p.avgPeiRaw());
        assertEquals(1, p.activeNodes()); assertEquals(1, p.idleNodes()); assertEquals(0, p.evaluableNodes());
        assertTrue(r.message().contains("전력 메트릭이 없어"), r.message());
    }

    @DisplayName("일부 노드만 전력이 있으면 그 노드만 PEI 에 들고 총량은 있는 것만 더한다")
    @Test
    void only_powered_nodes_enter_pei() {
        stub("node_cpu_seconds_total", List.of(series("n1", T1, 80), series("n2", T1, 40)));
        stub("ipmi_dcmi_power", List.of(series("n1", T1, 200)));

        Point p = service.trend(from, to, "5m").points().get(0);

        assertTrue(p.measured());
        assertEquals(1, p.evaluableNodes());
        assertEquals(200.0, p.totalPowerWatts(), EPS);
        assertEquals(0.40, p.avgPeiRaw(), EPS);
        assertEquals(2, p.totalNodes(), "CPU 가 있는 노드는 전부 센다");
    }

    @DisplayName("시각 결손 노드는 그 시각에서만 빠지고 CPU 가 전혀 없으면 빈 결과다")
    @Test
    void node_missing_at_point_drops_there() {
        stub("node_cpu_seconds_total", List.of(series("n1", T1, 80, T2, 70), series("n2", T1, 2)));   // n2 는 T2 결손
        stub("ipmi_dcmi_power", List.of(series("n1", T1, 200, T2, 200), series("n2", T1, 150)));

        PowerOptimizationTrendResponse r = service.trend(from, to, "5m");
        assertEquals(2, r.points().get(0).totalNodes());
        assertEquals(1, r.points().get(1).totalNodes(), "T2 에는 n1 만");

        stub("node_cpu_seconds_total", List.of());
        PowerOptimizationTrendResponse empty = service.trend(from, to, "5m");
        assertTrue(empty.points().isEmpty());
        assertTrue(empty.message().contains("CPU 메트릭이 없습니다"), empty.message());
    }

    @DisplayName("구간 검증은 400 이고 조회 실패는 사유를 담은 빈 결과 200 이다")
    @Test
    void range_400_query_failure_empty_200() {
        BusinessException e = assertThrows(BusinessException.class, () -> service.trend(to, from, "5m"));
        assertEquals(400, e.getStatus().value());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.trend(from.minusDays(2), to, "1s")).getStatus().value(), "지점 과다(2일/1초)");
        assertEquals(400, assertThrows(BusinessException.class, () -> service.trend(from, to, "abc")).getStatus().value());

        when(metrics.queryRangeResults(anyString(), any(), any(), anyString())).thenThrow(new ResourceAccessException("refused"));
        PowerOptimizationTrendResponse r = service.trend(from, to, "5m");
        assertTrue(r.points().isEmpty());
        assertTrue(r.message().contains("조회하지 못했습니다"), r.message());
    }

    @DisplayName("기본 구간은 설정의 24시간 5분이다")
    @Test
    void default_range_24h_5m_from_config() {
        stub("node_cpu_seconds_total", List.of());
        stub("ipmi_dcmi_power", List.of());
        PowerOptimizationTrendResponse r = service.trend(null, null, null);
        assertEquals("5m", r.step());
        assertEquals(24, java.time.Duration.between(r.from(), r.to()).toHours());
    }

    @DisplayName("IPMI 시리즈가 노드와 다른 instance(host:9290)로 와도 호스트명 일치로 노드에 이어진다")
    @Test
    void power_keyed_by_ipmi_instance_joins_the_node_by_host() {
        stub("node_cpu_seconds_total", List.of(series("n1:9100", T1, 80), series("n2:9100", T1, 40)));
        stub("ipmi_dcmi_power", List.of(series("n1:9290", T1, 200), series("n2:9290", T1, 100)));

        Point p = service.trend(from, to, "5m").points().get(0);

        assertTrue(p.measured(), "instance 가 달라도 전력이 노드에 붙어야 한다 — 이전에는 전부 미계측이었다");
        assertEquals(2, p.evaluableNodes());
        assertEquals(300.0, p.totalPowerWatts(), EPS);
    }

    @DisplayName("두 노드가 같은 IPMI 시리즈를 가리키면 둘 다 빠진다 — 한 값이 두 번 더해지면 합이 부푼다")
    @Test
    void nodes_sharing_one_ipmi_series_are_both_dropped() {
        // 원장: n1 이 n2 의 IPMI 를 가리킨다 → n2(호스트명 일치)와 충돌
        NodeExporterMapping m = new NodeExporterMapping("n1:9100");
        m.setIpmiInstance("n2:9290");
        NodeExporterMappingRepository ledger = mock(NodeExporterMappingRepository.class);
        when(ledger.findAll()).thenReturn(List.of(m));
        SchedulerProperties props = new SchedulerProperties();
        MetricsQueryGuard guard = new MetricsQueryGuard(2);
        com.innogrid.aims.scheduler.metrics.SchedulerMetricsGateway gateway = VictoriaMetricsGateway.of(metrics);
        PowerOptimizationService withLedger = new PowerOptimizationService(mock(SchedulingService.class), new PowerModel(props),
                props, gateway, guard, null, new ExporterInstanceResolver(ledger, gateway, guard, props));
        stub("node_cpu_seconds_total", List.of(series("n1:9100", T1, 80), series("n2:9100", T1, 40), series("n3:9100", T1, 50)));
        stub("ipmi_dcmi_power", List.of(series("n2:9290", T1, 100), series("n3:9290", T1, 150)));

        Point p = withLedger.trend(from, to, "5m").points().get(0);

        assertEquals(1, p.evaluableNodes(), "n3 만 남는다 — n1·n2 는 같은 시리즈를 가리켜 둘 다 미계측");
        assertEquals(150.0, p.totalPowerWatts(), EPS, "100 이 두 번 더해진 350 도, 한 번 더해진 250 도 아니다");
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private void stub(String fragment, List<Map<String, Object>> result) {
        when(metrics.queryRangeResults(contains(fragment), any(Instant.class), any(Instant.class), anyString())).thenReturn(result);
    }

    /** 저장소 응답 형태: {"metric": {"instance": …}, "values": [[초, "값"], …]} */
    private static Map<String, Object> series(String instance, Object... tsValuePairs) {
        List<List<Object>> values = new ArrayList<>();
        for (int i = 0; i + 1 < tsValuePairs.length; i += 2) {
            values.add(List.of(tsValuePairs[i], String.valueOf(tsValuePairs[i + 1])));
        }
        return Map.of("metric", Map.of("instance", instance), "values", values);
    }
}
