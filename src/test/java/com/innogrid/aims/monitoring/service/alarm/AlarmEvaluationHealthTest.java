package com.innogrid.aims.monitoring.service.alarm;

import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.log.PortalLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 평가기 장애 추적 — 상태 전이(정상→실패, 실패→정상)에서만 SYSTEM 행을 남기고, 장애 단위별로 독립이며, 기록 실패는 삼킨다. */
@ExtendWith(MockitoExtension.class)
class AlarmEvaluationHealthTest {

    @Mock PortalLogService portalLogService;
    AlarmEvaluationHealth health;

    @BeforeEach
    void setUp() {
        health = new AlarmEvaluationHealth(portalLogService);
    }

    @Test
    void 처음_실패에만_FAILURE_를_남기고_반복_실패는_기록하지_않는다() {
        health.failed(AlarmEvaluationHealth.SCOPE_KEY, "역할 스코프 조회", new IllegalStateException("tumblebug down"));
        health.failed(AlarmEvaluationHealth.SCOPE_KEY, "역할 스코프 조회", new IllegalStateException("tumblebug down"));
        health.failed(AlarmEvaluationHealth.SCOPE_KEY, "역할 스코프 조회", new IllegalStateException("still down"));

        verify(portalLogService, times(1)).system(eq("알람 평가기"), eq("장애"), eq("역할 스코프 조회 실패"),
                eq(PortalLogResult.FAILURE), eq("tumblebug down"));
        assertThat(health.failingKeys()).containsExactly("scope");
    }

    @Test
    void 실패_중이던_단위가_성공하면_SUCCESS_복구를_남기고_정상이던_단위의_성공은_기록하지_않는다() {
        String cpu = AlarmEvaluationHealth.metricKey(AlarmMetric.CPU_USED_PERCENT);
        health.recovered(cpu, "Thanos 조회(CPU_USED_PERCENT)");               // 정상 → 정상: 없음
        verify(portalLogService, never()).system(anyString(), anyString(), anyString(), any(), any());

        health.failed(cpu, "Thanos 조회(CPU_USED_PERCENT)", new RuntimeException("timeout"));
        health.recovered(cpu, "Thanos 조회(CPU_USED_PERCENT)");
        health.recovered(cpu, "Thanos 조회(CPU_USED_PERCENT)");

        verify(portalLogService, times(1)).system(eq("알람 평가기"), eq("장애"), eq("Thanos 조회(CPU_USED_PERCENT) 실패"),
                eq(PortalLogResult.FAILURE), eq("timeout"));
        verify(portalLogService, times(1)).system(eq("알람 평가기"), eq("복구"), eq("Thanos 조회(CPU_USED_PERCENT) 복구"),
                eq(PortalLogResult.SUCCESS), isNull());
        assertThat(health.failingKeys()).isEmpty();
    }

    @Test
    void 장애_단위는_서로_독립이고_원인_메시지가_없으면_예외_문자열을_쓴다() {
        health.failed(AlarmEvaluationHealth.metricKey(AlarmMetric.GPU_TEMP), "Thanos 조회(GPU_TEMP)", new RuntimeException());
        health.failed(AlarmEvaluationHealth.SCOPE_KEY, "역할 스코프 조회", new RuntimeException("x"));

        assertThat(health.failingKeys()).containsExactlyInAnyOrder("metric:GPU_TEMP", "scope");
        verify(portalLogService).system(eq("알람 평가기"), eq("장애"), eq("Thanos 조회(GPU_TEMP) 실패"),
                eq(PortalLogResult.FAILURE), eq("java.lang.RuntimeException"));

        health.recovered(AlarmEvaluationHealth.SCOPE_KEY, "역할 스코프 조회");
        assertThat(health.failingKeys()).containsExactly("metric:GPU_TEMP");
    }

    @Test
    void 포털_로그_기록이_실패해도_상태는_바뀌고_예외는_새지_않는다() {
        doThrow(new IllegalStateException("db down")).when(portalLogService).system(anyString(), anyString(), anyString(), any(), any());

        health.failed(AlarmEvaluationHealth.SCOPE_KEY, "역할 스코프 조회", new RuntimeException("x"));

        assertThat(health.failingKeys()).containsExactly("scope");
    }
}
