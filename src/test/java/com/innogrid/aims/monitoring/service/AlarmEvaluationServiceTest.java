package com.innogrid.aims.monitoring.service;

import java.util.List;
import java.util.Map;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmSource;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleAreaListItemResponse.AreaKind;
import com.innogrid.aims.monitoring.entity.AlarmHistory;
import com.innogrid.aims.monitoring.entity.AlarmRule;
import com.innogrid.aims.monitoring.entity.Event;
import com.innogrid.aims.monitoring.repository.AlarmHistoryRepository;
import com.innogrid.aims.monitoring.repository.AlarmRuleRepository;
import com.innogrid.aims.monitoring.repository.EventRepository;
import com.innogrid.aims.monitoring.service.alarm.AlarmMetric;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 평가기 — 메트릭 단위 묶음 쿼리(규칙 수와 무관), 카탈로그 코드·초안 별칭 해석, 자원 탐색기 라벨로 호스트/VM 식별,
 * 이력 스냅샷(코드·단위·기준 문자열), 카탈로그에 없는 메트릭·Thanos 장애·비정상 값 처리.
 */
@ExtendWith(MockitoExtension.class)
class AlarmEvaluationServiceTest {

    @Mock AlarmRuleRepository ruleRepository;
    @Mock AlarmHistoryRepository historyRepository;
    @Mock EventRepository eventRepository;
    @Mock VictoriaMetricsClient vmClient;

    AlarmEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new AlarmEvaluationService(ruleRepository, historyRepository, eventRepository, vmClient);
    }

    @Test
    void 같은_메트릭의_규칙은_쿼리_한_번으로_평가하고_초안_별칭도_같은_메트릭으로_묶인다() {
        when(ruleRepository.findByEnabledTrue()).thenReturn(List.of(
                new AlarmRule("cpu-80", "cpu_usage", "gt", 80, "WARNING", null),           // 초안 별칭
                new AlarmRule("cpu-90", "CPU_USED_PERCENT", "gte", 90, "critical", null))); // 카탈로그 코드
        when(vmClient.query(anyString())).thenReturn(vector(
                sample(Map.of("instance", "10.0.0.1:9100", "mdc_id", "mdc-01"), 92.4),
                sample(Map.of("instance", "10.0.0.2:9100", "mdc_id", "mdc-01"), 85.0),
                sample(Map.of("instance", "10.0.0.3:9100", "mdc_id", "mdc-02"), 20.0)));
        when(historyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.evaluateRules();

        ArgumentCaptor<String> promql = ArgumentCaptor.forClass(String.class);
        verify(vmClient, times(1)).query(promql.capture());
        assertThat(promql.getValue()).isEqualTo(AlarmMetric.CPU_USED_PERCENT.promql(
                AlarmEvaluationService.GROUP_BY, null, AlarmEvaluationService.RATE_WINDOW));
        assertThat(promql.getValue()).startsWith("100 - (avg by (instance, mdc_id, tb_ns, tb_infra, tb_node) (rate(node_cpu_seconds_total{mode=\"idle\"}[5m]))");

        // 92.4 → 두 규칙 모두, 85 → cpu-80 만, 20 → 없음 = 3건
        ArgumentCaptor<AlarmHistory> saved = ArgumentCaptor.forClass(AlarmHistory.class);
        verify(historyRepository, times(3)).save(saved.capture());
        verify(eventRepository, times(3)).save(any(Event.class));
        assertThat(saved.getAllValues()).extracting(AlarmHistory::getPolicyName, AlarmHistory::getResourceId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("cpu-80", "10.0.0.1:9100"),
                        org.assertj.core.groups.Tuple.tuple("cpu-90", "10.0.0.1:9100"),
                        org.assertj.core.groups.Tuple.tuple("cpu-80", "10.0.0.2:9100"));
    }

    @Test
    void 물리_호스트_이력은_카탈로그_코드_단위_기준_문자열과_MDC_영역을_스냅샷으로_남긴다() {
        when(ruleRepository.findByEnabledTrue()).thenReturn(List.of(
                new AlarmRule("mem-high", "mem_usage", "gt", 80, "CRITICAL", "email")));
        when(vmClient.query(anyString())).thenReturn(vector(
                sample(Map.of("instance", "10.0.0.1:9100", "mdc_id", "mdc-01"), 91.25)));
        when(historyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.evaluateRules();

        ArgumentCaptor<AlarmHistory> saved = ArgumentCaptor.forClass(AlarmHistory.class);
        verify(historyRepository).save(saved.capture());
        AlarmHistory a = saved.getValue();
        assertThat(a.getSource()).isEqualTo(AlarmSource.POLICY);
        assertThat(a.getLevel()).isEqualTo(AlarmLevel.CRITICAL);
        assertThat(a.getMetric()).isEqualTo("MEMORY_USED_PERCENT");
        assertThat(a.getUnit()).isEqualTo("%");
        assertThat(a.getCriterion()).isEqualTo("Memory Used > 80%");
        assertThat(a.getObservedValue()).isEqualTo(91.25);
        assertThat(a.getResourceType()).isEqualTo("node");
        assertThat(a.getResourceName()).isEqualTo("10.0.0.1:9100");
        assertThat(a.getAreaId()).isEqualTo("mdc-01");
        assertThat(a.getAreaKind()).isEqualTo(AreaKind.MDC);
        assertThat(a.getPolicyName()).isEqualTo("mem-high");
    }

    @Test
    void Tumblebug_라벨이_있으면_VM_자원과_Namespace_영역으로_기록한다() {
        when(ruleRepository.findByEnabledTrue()).thenReturn(List.of(
                new AlarmRule("fs-90", "FS_USED_PERCENT", "gte", 90, "WARNING", null)));
        when(vmClient.query(anyString())).thenReturn(vector(
                sample(Map.of("instance", "192.168.10.5:9100", "tb_ns", "openstack", "tb_infra", "infra-01", "tb_node", "vm-1"), 93.0)));
        when(historyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.evaluateRules();

        ArgumentCaptor<AlarmHistory> saved = ArgumentCaptor.forClass(AlarmHistory.class);
        verify(historyRepository).save(saved.capture());
        AlarmHistory a = saved.getValue();
        assertThat(a.getResourceType()).isEqualTo("vm");
        assertThat(a.getResourceId()).isEqualTo("TB:openstack/infra-01/vm-1");
        assertThat(a.getResourceName()).isEqualTo("vm-1");
        assertThat(a.getAreaId()).isEqualTo("TB:openstack");
        assertThat(a.getAreaKind()).isEqualTo(AreaKind.NAMESPACE);
        assertThat(a.getCriterion()).isEqualTo("FS Used >= 90%");
    }

    @Test
    void 카탈로그에_없는_메트릭_규칙은_쿼리_없이_건너뛴다() {
        when(ruleRepository.findByEnabledTrue()).thenReturn(List.of(
                new AlarmRule("load", "node_load1", "gt", 4, "WARNING", null)));

        service.evaluateRules();

        verify(vmClient, never()).query(anyString());
        verify(historyRepository, never()).save(any());
    }

    @Test
    void 한_메트릭의_Thanos_장애는_다른_메트릭_평가를_막지_않는다() {
        when(ruleRepository.findByEnabledTrue()).thenReturn(List.of(
                new AlarmRule("cpu", "CPU_USED_PERCENT", "gt", 80, "WARNING", null),
                new AlarmRule("gpu-temp", "gpu_temperature", "gt", 85, "CRITICAL", null)));
        when(vmClient.query(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.contains("node_cpu_seconds_total")) throw new IllegalStateException("thanos down");
            return vector(sample(Map.of("instance", "10.0.0.1:9400", "mdc_id", "mdc-01"), 88.0));
        });
        when(historyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.evaluateRules();

        verify(vmClient, times(2)).query(anyString());
        ArgumentCaptor<AlarmHistory> saved = ArgumentCaptor.forClass(AlarmHistory.class);
        verify(historyRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getMetric()).isEqualTo("GPU_TEMP");
        assertThat(saved.getValue().getUnit()).isEqualTo("°C");
        assertThat(saved.getValue().getCriterion()).isEqualTo("GPU Temperature > 85°C");
    }

    @Test
    void NaN_값과_라벨_없는_표본은_판정하지_않고_임계값_미만은_저장하지_않는다() {
        when(ruleRepository.findByEnabledTrue()).thenReturn(List.of(
                new AlarmRule("cpu", "CPU_USED_PERCENT", "gt", 80, "WARNING", null)));
        when(vmClient.query(anyString())).thenReturn(vector(
                sample(Map.of("instance", "10.0.0.1:9100"), "NaN"),
                Map.of("value", List.of(1.0, "99")),                          // metric 라벨 없음
                sample(Map.of("instance", "10.0.0.2:9100"), 79.9)));

        service.evaluateRules();

        verify(historyRepository, never()).save(any());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void 활성_규칙이_없으면_쿼리하지_않는다() {
        when(ruleRepository.findByEnabledTrue()).thenReturn(List.of());

        service.evaluateRules();

        verify(vmClient, never()).query(anyString());
    }

    @Test
    void 조건_코드는_기호로_바뀌고_대소문자를_가리지_않는다() {
        assertThat(AlarmEvaluationService.symbolOf("gt")).isEqualTo(">");
        assertThat(AlarmEvaluationService.symbolOf("GTE")).isEqualTo(">=");
        assertThat(AlarmEvaluationService.symbolOf("lte")).isEqualTo("<=");
        assertThat(AlarmEvaluationService.isThresholdExceeded("LT", 1.0, 2.0)).isTrue();
        assertThat(AlarmEvaluationService.isThresholdExceeded("between", 1.0, 2.0)).isFalse();
        assertThat(AlarmEvaluationService.isThresholdExceeded(null, 1.0, 2.0)).isFalse();
    }

    // ── Prometheus 즉시 쿼리 응답 모양 ──────────────────────────────

    @SafeVarargs
    private static Map<String, Object> vector(Map<String, Object>... results) {
        return Map.of("status", "success", "data", Map.of("resultType", "vector", "result", List.of(results)));
    }

    private static Map<String, Object> sample(Map<String, String> labels, Object value) {
        return Map.of("metric", labels, "value", List.of(1_757_300_000.0, String.valueOf(value)));
    }
}
