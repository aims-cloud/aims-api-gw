package com.innogrid.aims.scheduler.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PowerOptimizationTrendResponse;
import com.innogrid.aims.scheduler.dto.PowerOptimizationTrendResponse.Point;
import com.innogrid.aims.scheduler.engine.model.PowerModel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 클러스터 전력 최적화 추세 — 시각별 계산이 현재 진단과 같은 식인가, 결손·미계측·조회 실패를 설계대로 다루는가.
 *
 * <p>합성 시계열: 노드 3대(용량 기본 500W, 유휴 전력 = 500 × 0.3 = 150W), 가동 기준 CPU 5%.
 * t1 — n1 80%/200W · n2 2%/160W(유휴) · n3 1%/150W(유휴) → 가동 1·유휴 2·총 510W·회수 300W·PEI 0.40/0.0125/0.0067
 * t2 — n1 70%/200W · n2 60%/210W · n3 1%/150W(유휴)       → 가동 2·유휴 1·회수 150W
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

    @Test
    void 시각마다_가동_유휴_회수_가능_전력_PEI_를_현재_진단과_같은_식으로_계산한다() {
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
        assertEquals(300.0, p1.reclaimableIdleWatts(), EPS, "유휴 2대 × 150W");
        assertEquals(0.40, p1.maxPeiRaw(), EPS);          // 80/200
        assertEquals(0.0067, p1.minPeiRaw(), 1e-3);       // 1/150
        assertEquals(3, p1.evaluableNodes()); assertTrue(p1.measured());

        assertEquals(2, p2.activeNodes()); assertEquals(150.0, p2.reclaimableIdleWatts(), EPS);
        assertEquals(3, p3.activeNodes()); assertEquals(0.0, p3.reclaimableIdleWatts(), EPS);
        assertTrue(p1.reclaimableIdleWatts() > p2.reclaimableIdleWatts() && p2.reclaimableIdleWatts() > p3.reclaimableIdleWatts(),
                "회수 가능 전력이 300 → 150 → 0 으로 줄어든다");
    }

    @Test
    void 전력_메트릭이_없으면_measured_false_전력_지표_null_가동_수만_낸다() {
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

    @Test
    void 일부_노드만_전력이_있으면_그_노드만_PEI_에_들고_총량은_있는_것만_더한다() {
        stub("node_cpu_seconds_total", List.of(series("n1", T1, 80), series("n2", T1, 40)));
        stub("ipmi_dcmi_power", List.of(series("n1", T1, 200)));

        Point p = service.trend(from, to, "5m").points().get(0);

        assertTrue(p.measured());
        assertEquals(1, p.evaluableNodes());
        assertEquals(200.0, p.totalPowerWatts(), EPS);
        assertEquals(0.40, p.avgPeiRaw(), EPS);
        assertEquals(2, p.totalNodes(), "CPU 가 있는 노드는 전부 센다");
    }

    @Test
    void 시각_결손_노드는_그_시각에서만_빠지고_CPU_가_전혀_없으면_빈_결과다() {
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

    @Test
    void 구간_검증은_400_이고_조회_실패는_사유를_담은_빈_결과_200_이다() {
        BusinessException e = assertThrows(BusinessException.class, () -> service.trend(to, from, "5m"));
        assertEquals(400, e.getStatus().value());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.trend(from.minusDays(2), to, "1s")).getStatus().value(), "지점 과다(2일/1초)");
        assertEquals(400, assertThrows(BusinessException.class, () -> service.trend(from, to, "abc")).getStatus().value());

        when(metrics.queryRangeResults(anyString(), any(), any(), anyString())).thenThrow(new ResourceAccessException("refused"));
        PowerOptimizationTrendResponse r = service.trend(from, to, "5m");
        assertTrue(r.points().isEmpty());
        assertTrue(r.message().contains("조회하지 못했습니다"), r.message());
    }

    @Test
    void 기본_구간은_설정의_24시간_5분이다() {
        stub("node_cpu_seconds_total", List.of());
        stub("ipmi_dcmi_power", List.of());
        PowerOptimizationTrendResponse r = service.trend(null, null, null);
        assertEquals("5m", r.step());
        assertEquals(24, java.time.Duration.between(r.from(), r.to()).toHours());
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
