package com.innogrid.aims.scheduler.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PowerEfficiencyTrendResponse;
import com.innogrid.aims.scheduler.engine.model.PowerModel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 추세는 메트릭 저장소를 그때그때 읽는다. 저장소가 없거나 데이터가 비는 상황이
 * 오류가 아니라 정상 응답이어야 하므로, 그 경로를 중심으로 고정한다.
 */
class PowerEfficiencyTrendTest {

    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-09-01T00:00:00Z");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-09-01T01:00:00Z");

    private VictoriaMetricsClient metrics;
    private PowerEfficiencyService service;

    @BeforeEach
    void setUp() {
        SchedulerProperties properties = new SchedulerProperties();
        metrics = mock(VictoriaMetricsClient.class);
        service = new PowerEfficiencyService(
                mock(SchedulingService.class), new PowerModel(properties), metrics, properties);
    }

    // ── 정상 경로 ──────────────────────────────────────────────

    @Test
    void 전력과_CPU_를_시각으로_맞춰_PEI_를_계산한다() {
        stub("ipmi_dcmi_power", series(1756684800L, 200.0, 1756685100L, 250.0));
        stub("node_cpu_seconds_total", series(1756684800L, 80.0, 1756685100L, 50.0));

        List<PowerEfficiencyTrendResponse.Point> points =
                service.trend("node-1", FROM, TO, "5m").points();

        assertEquals(2, points.size());
        assertEquals(0.40, points.get(0).peiRaw(), 1e-9, "80 / 200");
        assertEquals(0.20, points.get(1).peiRaw(), 1e-9, "50 / 250");
    }

    @Test
    void 시각_순서대로_돌려준다() {
        stub("ipmi_dcmi_power", series(1756685100L, 250.0, 1756684800L, 200.0));
        stub("node_cpu_seconds_total", series(1756684800L, 80.0, 1756685100L, 50.0));

        List<PowerEfficiencyTrendResponse.Point> points =
                service.trend("node-1", FROM, TO, "5m").points();

        assertTrue(points.get(0).at().isBefore(points.get(1).at()));
    }

    @Test
    void 한쪽_시계열만_있는_지점은_PEI_를_비운다() {
        // 두 메트릭의 수집 시각이 어긋나면 생기는 상황이다. 없는 값으로 나눌 수 없다.
        stub("ipmi_dcmi_power", series(1756684800L, 200.0));
        stub("node_cpu_seconds_total", series(1756685100L, 50.0));

        List<PowerEfficiencyTrendResponse.Point> points =
                service.trend("node-1", FROM, TO, "5m").points();

        assertEquals(2, points.size());
        assertNull(points.get(0).peiRaw(), "CPU 가 없는 지점");
        assertEquals(200.0, points.get(0).powerWatts(), 1e-9);
        assertNull(points.get(1).peiRaw(), "전력이 없는 지점");
        assertEquals(50.0, points.get(1).cpuUsagePercent(), 1e-9);
    }

    @Test
    void 전력이_0_인_지점은_PEI_를_비운다() {
        stub("ipmi_dcmi_power", series(1756684800L, 0.0));
        stub("node_cpu_seconds_total", series(1756684800L, 80.0));

        assertNull(service.trend("node-1", FROM, TO, "5m").points().get(0).peiRaw());
    }

    // ── 데이터가 없을 때 ────────────────────────────────────────

    @Test
    void 데이터가_없으면_빈_결과와_사유를_돌려준다() {
        when(metrics.queryRangeResults(anyString(), any(), any(), anyString())).thenReturn(List.of());

        PowerEfficiencyTrendResponse result = service.trend("node-1", FROM, TO, "5m");

        assertTrue(result.points().isEmpty());
        assertTrue(result.message().contains("IPMI"), result.message());
    }

    @Test
    void 저장소_조회에_실패해도_예외를_올리지_않는다() {
        // 조회 실패로 500 을 내면 화면이 통째로 깨진다. IPMI 미연동 환경에서는 흔한 상황이다.
        when(metrics.queryRangeResults(anyString(), any(), any(), anyString()))
                .thenThrow(new IllegalStateException("connection refused"));

        PowerEfficiencyTrendResponse result = service.trend("node-1", FROM, TO, "5m");

        assertTrue(result.points().isEmpty());
        assertTrue(result.message().contains("조회하지 못했습니다"), result.message());
    }

    @Test
    void 전력만_없으면_왜_PEI_가_비었는지_알려준다() {
        // 배포 VM 이 실제로 이 상태다 — CPU 시계열은 있고 IPMI 만 없다.
        // 사유가 없으면 화면은 "PEI 가 왜 전부 비었는지" 알 길이 없다.
        stub("ipmi_dcmi_power", List.of());
        stub("node_cpu_seconds_total", series(1756684800L, 80.0, 1756685100L, 50.0));

        PowerEfficiencyTrendResponse result = service.trend("node-1", FROM, TO, "5m");

        assertEquals(2, result.points().size(), "CPU 추이는 그대로 준다");
        assertNull(result.points().get(0).peiRaw());
        assertNotNull(result.message(), "전력이 없다는 사유를 알려야 한다");
        assertTrue(result.message().contains("전력"), result.message());
    }

    @Test
    void 양쪽_다_있으면_사유를_붙이지_않는다() {
        stub("ipmi_dcmi_power", series(1756684800L, 200.0));
        stub("node_cpu_seconds_total", series(1756684800L, 80.0));

        assertNull(service.trend("node-1", FROM, TO, "5m").message());
    }

    // ── 파라미터 검증 ───────────────────────────────────────────

    @Test
    void 기본_구간은_최근_24시간이고_간격은_5분이다() {
        when(metrics.queryRangeResults(anyString(), any(), any(), anyString())).thenReturn(List.of());

        PowerEfficiencyTrendResponse result = service.trend("node-1", null, null, null);

        assertEquals("5m", result.step());
        assertEquals(24, java.time.Duration.between(result.from(), result.to()).toHours());
    }

    @Test
    void from_이_to_보다_뒤면_400_이다() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.trend("node-1", TO, FROM, "5m"));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }

    @Test
    void step_형식이_틀리면_400_이다() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(BusinessException.class,
                        () -> service.trend("node-1", FROM, TO, "5분")).getStatus());
        assertThrows(BusinessException.class, () -> service.trend("node-1", FROM, TO, "0m"));
    }

    @Test
    void 지점_수가_상한을_넘으면_400_이다() {
        // 메트릭 클라이언트에 읽기 타임아웃이 없어, 무거운 질의는 애초에 내보내지 않는다.
        OffsetDateTime longAgo = TO.minusDays(30);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.trend("node-1", longAgo, TO, "1s"));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("너무 많습니다"), e.getMessage());
    }

    @Test
    void 넓은_구간도_간격이_충분하면_통과한다() {
        when(metrics.queryRangeResults(anyString(), any(), any(), anyString())).thenReturn(List.of());

        assertDoesNotThrow(() -> service.trend("node-1", TO.minusDays(30), TO, "1h"));
    }

    // ── 도우미 ─────────────────────────────────────────────────

    /** PromQL 일부가 일치하는 호출에 시계열을 물린다. */
    private void stub(String queryFragment, List<Map<String, Object>> result) {
        when(metrics.queryRangeResults(contains(queryFragment), any(Instant.class),
                any(Instant.class), anyString())).thenReturn(result);
    }

    /** 저장소 응답 형태: {"values": [[초, "값"], ...]} */
    private List<Map<String, Object>> series(Object... timestampValuePairs) {
        List<List<Object>> values = new java.util.ArrayList<>();
        for (int i = 0; i < timestampValuePairs.length; i += 2) {
            values.add(List.of(timestampValuePairs[i], String.valueOf(timestampValuePairs[i + 1])));
        }
        return List.of(Map.of("metric", Map.of(), "values", values));
    }

    @SuppressWarnings("unused")
    private OffsetDateTime utc(long epochSecond) {
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC);
    }
}
