package com.innogrid.aims.scheduler.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

    @DisplayName("전력과 CPU 를 시각으로 맞춰 PEI 를 계산한다")
    @Test
    void power_and_cpu_aligned_by_time() {
        stub("ipmi_dcmi_power", series(1756684800L, 200.0, 1756685100L, 250.0));
        stub("node_cpu_seconds_total", series(1756684800L, 80.0, 1756685100L, 50.0));

        List<PowerEfficiencyTrendResponse.Point> points =
                service.trend("node-1", FROM, TO, "5m").points();

        assertEquals(2, points.size());
        assertEquals(0.40, points.get(0).peiRaw(), 1e-9, "80 / 200");
        assertEquals(0.20, points.get(1).peiRaw(), 1e-9, "50 / 250");
    }

    @DisplayName("시각 순서대로 돌려준다")
    @Test
    void points_in_timestamp_order() {
        stub("ipmi_dcmi_power", series(1756685100L, 250.0, 1756684800L, 200.0));
        stub("node_cpu_seconds_total", series(1756684800L, 80.0, 1756685100L, 50.0));

        List<PowerEfficiencyTrendResponse.Point> points =
                service.trend("node-1", FROM, TO, "5m").points();

        assertTrue(points.get(0).at().isBefore(points.get(1).at()));
    }

    @DisplayName("한쪽 시계열만 있는 지점은 PEI 를 비운다")
    @Test
    void one_series_only_empty_pei() {
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

    @DisplayName("전력이 0 인 지점은 PEI 를 비운다")
    @Test
    void power_0_point_empty_pei() {
        stub("ipmi_dcmi_power", series(1756684800L, 0.0));
        stub("node_cpu_seconds_total", series(1756684800L, 80.0));

        assertNull(service.trend("node-1", FROM, TO, "5m").points().get(0).peiRaw());
    }

    // ── 데이터가 없을 때 ────────────────────────────────────────

    @DisplayName("데이터가 없으면 빈 결과와 사유를 돌려준다")
    @Test
    void no_data_empty_result_with_reason() {
        when(metrics.queryRangeResults(anyString(), any(), any(), anyString())).thenReturn(List.of());

        PowerEfficiencyTrendResponse result = service.trend("node-1", FROM, TO, "5m");

        assertTrue(result.points().isEmpty());
        assertTrue(result.message().contains("IPMI"), result.message());
    }

    @DisplayName("저장소 조회에 실패해도 예외를 올리지 않는다")
    @Test
    void query_failure_raises_no_exception() {
        // 조회 실패로 500을 내면 화면이 통째로 깨진다. IPMI 미연동 환경에서는 흔한 상황이다.
        when(metrics.queryRangeResults(anyString(), any(), any(), anyString()))
                .thenThrow(new IllegalStateException("connection refused"));

        PowerEfficiencyTrendResponse result = service.trend("node-1", FROM, TO, "5m");

        assertTrue(result.points().isEmpty());
        assertTrue(result.message().contains("조회하지 못했습니다"), result.message());
    }

    @DisplayName("전력만 없으면 왜 PEI 가 비었는지 알려준다")
    @Test
    void missing_power_explains_empty_pei() {
        // 배포 VM이 실제로 이 상태다 — CPU 시계열은 있고 IPMI만 없다.
        // 사유가 없으면 화면은 "PEI가 왜 전부 비었는지" 알 길이 없다.
        stub("ipmi_dcmi_power", List.of());
        stub("node_cpu_seconds_total", series(1756684800L, 80.0, 1756685100L, 50.0));

        PowerEfficiencyTrendResponse result = service.trend("node-1", FROM, TO, "5m");

        assertEquals(2, result.points().size(), "CPU 추이는 그대로 준다");
        assertNull(result.points().get(0).peiRaw());
        assertNotNull(result.message(), "전력이 없다는 사유를 알려야 한다");
        assertTrue(result.message().contains("전력"), result.message());
    }

    @DisplayName("양쪽 다 있으면 사유를 붙이지 않는다")
    @Test
    void both_series_present_no_reason() {
        stub("ipmi_dcmi_power", series(1756684800L, 200.0));
        stub("node_cpu_seconds_total", series(1756684800L, 80.0));

        assertNull(service.trend("node-1", FROM, TO, "5m").message());
    }

    // ── 파라미터 검증 ───────────────────────────────────────────

    @DisplayName("기본 구간은 최근 24시간이고 간격은 5분이다")
    @Test
    void default_range_24h_step_5m() {
        when(metrics.queryRangeResults(anyString(), any(), any(), anyString())).thenReturn(List.of());

        PowerEfficiencyTrendResponse result = service.trend("node-1", null, null, null);

        assertEquals("5m", result.step());
        assertEquals(24, java.time.Duration.between(result.from(), result.to()).toHours());
    }

    @DisplayName("from 이 to 보다 뒤면 400 이다")
    @Test
    void from_after_to_400() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.trend("node-1", TO, FROM, "5m"));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }

    @DisplayName("step 형식이 틀리면 400 이다")
    @Test
    void invalid_step_format_400() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(BusinessException.class,
                        () -> service.trend("node-1", FROM, TO, "5분")).getStatus());
        assertThrows(BusinessException.class, () -> service.trend("node-1", FROM, TO, "0m"));
    }

    @DisplayName("지점 수가 상한을 넘으면 400 이다")
    @Test
    void too_many_points_400() {
        // 메트릭 클라이언트에 읽기 타임아웃이 없어, 무거운 질의는 애초에 내보내지 않는다.
        OffsetDateTime longAgo = TO.minusDays(30);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.trend("node-1", longAgo, TO, "1s"));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("너무 많습니다"), e.getMessage());
    }

    @DisplayName("넓은 구간도 간격이 충분하면 통과한다")
    @Test
    void wide_range_large_step_passes() {
        when(metrics.queryRangeResults(anyString(), any(), any(), anyString())).thenReturn(List.of());

        assertDoesNotThrow(() -> service.trend("node-1", TO.minusDays(30), TO, "1h"));
    }

    @DisplayName("전력은 저장소에 있는 IPMI instance(host:9290)로 묻는다 — 노드 instance 로 물으면 빈손이다")
    @Test
    void power_is_queried_by_the_discovered_ipmi_instance() {
        when(metrics.queryResults(contains("ipmi_dcmi_power_consumption_watts")))
                .thenReturn(List.of(Map.of("metric", Map.of("instance", "node-1:9290"), "value", List.of(1L, "1"))));
        stub("instance=\"node-1:9290\"", series(1756684800L, 200.0));
        stub("node_cpu_seconds_total", series(1756684800L, 80.0));

        List<PowerEfficiencyTrendResponse.Point> points = service.trend("node-1:9100", FROM, TO, "5m").points();

        assertEquals(1, points.size(), "node-1:9100 으로 물었다면 스텁에 걸리지 않아 전력이 없다");
        assertEquals(0.40, points.get(0).peiRaw(), 1e-9);
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
