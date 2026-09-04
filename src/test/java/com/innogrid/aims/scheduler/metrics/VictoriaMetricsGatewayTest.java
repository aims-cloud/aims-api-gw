package com.innogrid.aims.scheduler.metrics;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 기본 게이트웨이는 클라이언트에 그대로 위임한다 — 인자·반환 모두 손대지 않는다. */
class VictoriaMetricsGatewayTest {

    @DisplayName("네 질의가 같은 인자로 클라이언트에 전달되고 반환값은 그대로다. null 클라이언트는 null 게이트웨이")
    @Test
    void delegates_all_four() {
        VictoriaMetricsClient client = mock(VictoriaMetricsClient.class);
        when(client.query("q1")).thenReturn(Map.of("status", "success"));
        when(client.queryResults("q2")).thenReturn(List.of(Map.of("metric", Map.of())));
        when(client.queryScalar("q3")).thenReturn(4.2);
        Instant s = Instant.ofEpochSecond(1), e = Instant.ofEpochSecond(2);
        when(client.queryRangeResults("q4", s, e, "5m")).thenReturn(List.of());

        SchedulerMetricsGateway gw = VictoriaMetricsGateway.of(client);
        assertEquals("success", gw.query("q1").get("status"));
        assertEquals(1, gw.queryResults("q2").size());
        assertEquals(4.2, gw.queryScalar("q3"));
        assertTrue(gw.queryRangeResults("q4", s, e, "5m").isEmpty());
        assertEquals("victoria", gw.source());
        verify(client).queryRangeResults("q4", s, e, "5m");
        assertNull(VictoriaMetricsGateway.of(null));
    }
}
