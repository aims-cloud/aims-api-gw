package com.innogrid.aims.scheduler.service;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PowerEfficiencyTrendResponse;
import com.innogrid.aims.scheduler.engine.model.PowerModel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 전력 효율 추세는 수집기를 거치지 않고 저장소를 직접 부른다 — 저장소가 응답을 안 주면 이 API가 무한 대기하던 경로다.
 * 보호 장치를 거치면 상한 안에 돌아오고, 기존 처리대로 사유를 담은 빈 결과(200)가 된다.
 */
class PowerEfficiencyTrendTimeoutTest {

    private final CountDownLatch release = new CountDownLatch(1);
    private VictoriaMetricsClient metrics;
    private PowerEfficiencyService service;

    @BeforeEach
    void setUp() {
        SchedulerProperties properties = new SchedulerProperties();
        properties.getMetrics().setQueryTimeoutMs(150);
        metrics = mock(VictoriaMetricsClient.class);
        service = new PowerEfficiencyService(mock(SchedulingService.class), new PowerModel(properties), metrics, properties);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
    }

    @DisplayName("저장소가 응답하지 않으면 상한 안에 돌아오고 사유를 담은 빈 결과다")
    @Test
    void unresponsive_store_empty_with_reason() {
        when(metrics.queryRangeResults(anyString(), any(), any(), anyString())).thenAnswer(inv -> {
            release.await(10, TimeUnit.SECONDS);
            return List.of();
        });
        long started = System.nanoTime();

        PowerEfficiencyTrendResponse r = service.trend("n1:9100", null, null, null);

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000, "무한 대기하지 않는다");
        assertTrue(r.points().isEmpty());
        assertNotNull(r.message());
        assertTrue(r.message().contains("조회하지 못했습니다") && r.message().contains("150ms"), r.message());
    }

    @DisplayName("연결 실패도 같은 형태로 사유를 남긴다")
    @Test
    void connection_failure_same_shape() {
        when(metrics.queryRangeResults(anyString(), any(), any(), anyString()))
                .thenThrow(new ResourceAccessException("Connection refused"));

        PowerEfficiencyTrendResponse r = service.trend("n1:9100", null, null, null);

        assertTrue(r.points().isEmpty());
        assertTrue(r.message().contains("연결하지 못했습니다"), r.message());
    }
}
