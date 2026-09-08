package com.innogrid.aims.monitoring.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmHistoryStatus;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmSource;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmChannel;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmConditionOperator;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyType;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmTargetScope;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmTargetType;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleAreaListItemResponse.AreaKind;
import com.innogrid.aims.monitoring.entity.AlarmHistory;
import com.innogrid.aims.monitoring.entity.AlarmPolicy;
import com.innogrid.aims.monitoring.entity.AlarmPolicy.RecipientRef;
import com.innogrid.aims.monitoring.repository.AlarmHistoryRepository;
import com.innogrid.aims.monitoring.repository.AlarmPolicyRepository;
import com.innogrid.aims.monitoring.service.alarm.AlarmMetric;
import com.innogrid.aims.monitoring.service.alarm.AlarmStateChangedEvent;
import com.innogrid.aims.monitoring.service.alarm.AlarmStateChangedEvent.Transition;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedVm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 평가기 — 메트릭 단위 묶음 쿼리(정책 수와 무관), 정책 대상(역할 스코프·영역·개별 대상)으로 표본 필터, 호스트는 instance,
 * VM 은 TB id 로 매칭, 이력 스냅샷(정책·역할·영역·코드·단위·기준·fingerprint), 지속 시간, 중복 억제·해소,
 * 발생·해소 전이 이벤트 발행, 스코프 조회 실패·Thanos 장애·비정상 값 처리.
 *
 * 대역 스코프: ADMIN 역할에 호스트 asset 1(node-01, mdc-01, 10.0.0.1:9100)·asset 2(node-02, mdc-02, 10.0.0.2:9100),
 *              Namespace openstack 의 VM TB:openstack/infra-01/vm-1. USER 역할에는 asset 3(node-03, mdc-01, 10.0.0.3:9100).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlarmEvaluationServiceTest {

    @Mock AlarmPolicyRepository policyRepository;
    @Mock AlarmHistoryRepository historyRepository;
    @Mock VictoriaMetricsClient vmClient;
    @Mock ResourceScopeService resourceScopeService;
    @Mock ApplicationEventPublisher eventPublisher;

    AlarmEvaluationService service;

    static final Scope SCOPE = new Scope(Set.of("ADMIN", "USER"),
            List.of(new ScopedHost("ADMIN", 1L, "node-01", "n1", "mdc-01", null, "10.0.0.1:9100", List.of()),
                    new ScopedHost("ADMIN", 2L, "node-02", null, "mdc-02", null, "10.0.0.2:9100", List.of()),
                    new ScopedHost("USER", 3L, "node-03", null, "mdc-01", null, "10.0.0.3:9100", List.of())),
            List.of(new ScopedVm("ADMIN", "TB:openstack/infra-01/vm-1", "vm-1", null, null, "openstack"),
                    new ScopedVm("ADMIN", "SEBU1:ext-9", "sebu-vm", null, "mdc-01", null)),
            List.of());

    @BeforeEach
    void setUp() {
        service = new AlarmEvaluationService(policyRepository, historyRepository, vmClient, resourceScopeService, eventPublisher);
        when(resourceScopeService.scopeOf(anyCollection())).thenReturn(SCOPE);
        when(historyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    static AlarmPolicy policy(String name, String role, String areaId, String resourceType, AlarmTargetScope scope,
                              Set<String> targets, AlarmMetric metric, AlarmConditionOperator op, double threshold,
                              AlarmLevel level, int duration) {
        return new AlarmPolicy(name, role, areaId, resourceType, scope, targets, metric, op, threshold, level, duration,
                List.of(AlarmChannel.EMAIL), List.of(new RecipientRef(AlarmTargetType.INDIVIDUAL, "1")), null, "admin@innogrid.com");
    }

    static AlarmPolicy nodeAll(String name, AlarmMetric metric, double threshold) {
        return policy(name, "ADMIN", null, "node", AlarmTargetScope.ALL, Set.of(), metric, AlarmConditionOperator.GT, threshold,
                AlarmLevel.WARNING, 0);
    }

    @Test
    void 같은_메트릭의_정책은_쿼리_한_번으로_평가하고_역할_스코프_밖_자원은_건너뛴다() {
        when(policyRepository.findByEnabledTrueAndType(AlarmPolicyType.METRIC)).thenReturn(List.of(
                nodeAll("cpu-80", AlarmMetric.CPU_USED_PERCENT, 80),
                policy("cpu-90", "ADMIN", null, "node", AlarmTargetScope.ALL, Set.of(), AlarmMetric.CPU_USED_PERCENT,
                        AlarmConditionOperator.GTE, 90, AlarmLevel.CRITICAL, 0)));
        when(vmClient.query(anyString())).thenReturn(vector(
                sample(Map.of("instance", "10.0.0.1:9100", "mdc_id", "mdc-01"), 92.4),
                sample(Map.of("instance", "10.0.0.2:9100", "mdc_id", "mdc-02"), 85.0),
                sample(Map.of("instance", "10.0.0.3:9100", "mdc_id", "mdc-01"), 99.0),      // USER 역할 자원 — ADMIN 정책 대상 아님
                sample(Map.of("instance", "10.0.0.9:9100", "mdc_id", "mdc-01"), 99.0)));    // 스코프 밖

        service.evaluatePolicies();

        ArgumentCaptor<String> promql = ArgumentCaptor.forClass(String.class);
        verify(vmClient, times(1)).query(promql.capture());
        assertThat(promql.getValue()).isEqualTo(AlarmMetric.CPU_USED_PERCENT.promql(
                AlarmEvaluationService.GROUP_BY, null, AlarmEvaluationService.RATE_WINDOW));

        // 92.4(asset 1) → 두 정책, 85(asset 2) → cpu-80 만 = 3건. asset 3·미등록 instance 는 없음
        ArgumentCaptor<AlarmHistory> saved = ArgumentCaptor.forClass(AlarmHistory.class);
        verify(historyRepository, times(3)).save(saved.capture());
        verify(eventPublisher, times(3)).publishEvent(any(AlarmStateChangedEvent.class));
        assertThat(saved.getAllValues()).extracting(AlarmHistory::getPolicyName, AlarmHistory::getResourceId, AlarmHistory::getResourceName)
                .containsExactlyInAnyOrder(tuple("cpu-80", "1", "node-01"), tuple("cpu-90", "1", "node-01"), tuple("cpu-80", "2", "node-02"));
    }

    @Test
    void 물리_호스트_이력은_정책_역할_MDC_영역_코드_단위_기준_문자열을_스냅샷으로_남긴다() {
        when(policyRepository.findByEnabledTrueAndType(AlarmPolicyType.METRIC)).thenReturn(List.of(
                policy("mem-high", "ADMIN", "mdc-01", "node", AlarmTargetScope.ALL, Set.of(), AlarmMetric.MEMORY_USED_PERCENT,
                        AlarmConditionOperator.GT, 80, AlarmLevel.CRITICAL, 0)));
        when(vmClient.query(anyString())).thenReturn(vector(
                sample(Map.of("instance", "10.0.0.1:9100", "mdc_id", "mdc-01"), 91.25),
                sample(Map.of("instance", "10.0.0.2:9100", "mdc_id", "mdc-02"), 95.0)));     // 영역 mdc-02 — 정책 영역 밖

        service.evaluatePolicies();

        ArgumentCaptor<AlarmHistory> saved = ArgumentCaptor.forClass(AlarmHistory.class);
        verify(historyRepository).save(saved.capture());
        AlarmHistory a = saved.getValue();
        assertThat(a.getSource()).isEqualTo(AlarmSource.POLICY);
        assertThat(a.getLevel()).isEqualTo(AlarmLevel.CRITICAL);
        assertThat(a.getPolicyName()).isEqualTo("mem-high");
        assertThat(a.getRoleName()).isEqualTo("ADMIN");
        assertThat(a.getMetric()).isEqualTo("MEMORY_USED_PERCENT");
        assertThat(a.getUnit()).isEqualTo("%");
        assertThat(a.getCriterion()).isEqualTo("Memory Used > 80%");
        assertThat(a.getObservedValue()).isEqualTo(91.25);
        assertThat(a.getResourceType()).isEqualTo("node");
        assertThat(a.getResourceId()).isEqualTo("1");
        assertThat(a.getResourceName()).isEqualTo("node-01");
        assertThat(a.getAreaId()).isEqualTo("mdc-01");
        assertThat(a.getAreaKind()).isEqualTo(AreaKind.MDC);
        assertThat(a.getFingerprint()).hasSize(64);

        ArgumentCaptor<AlarmStateChangedEvent> event = ArgumentCaptor.forClass(AlarmStateChangedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        AlarmStateChangedEvent firing = event.getValue();
        assertThat(firing.transition()).isEqualTo(Transition.FIRING);
        assertThat(firing.isFiring()).isTrue();
        assertThat(firing.source()).isEqualTo(AlarmSource.POLICY);
        assertThat(firing.level()).isEqualTo(AlarmLevel.CRITICAL);
        assertThat(firing.policyName()).isEqualTo("mem-high");
        assertThat(firing.roleName()).isEqualTo("ADMIN");
        assertThat(firing.resourceType()).isEqualTo("node");
        assertThat(firing.resourceId()).isEqualTo("1");
        assertThat(firing.resourceName()).isEqualTo("node-01");
        assertThat(firing.areaId()).isEqualTo("mdc-01");
        assertThat(firing.mdcId()).isEqualTo("mdc-01");
        assertThat(firing.metric()).isEqualTo("MEMORY_USED_PERCENT");
        assertThat(firing.criterion()).isEqualTo("Memory Used > 80%");
        assertThat(firing.observedValue()).isEqualTo(91.25);
        assertThat(firing.occurredAt()).isEqualTo(a.getOccurredAt());
    }

    @Test
    void VM_정책은_tb_라벨을_TB_id로_대조해_Namespace_영역으로_기록하고_개별_대상은_선택한_자원만_본다() {
        when(policyRepository.findByEnabledTrueAndType(AlarmPolicyType.METRIC)).thenReturn(List.of(
                policy("fs-90", "ADMIN", "TB:openstack", "vm", AlarmTargetScope.INDIVIDUAL, Set.of("TB:openstack/infra-01/vm-1"),
                        AlarmMetric.FS_USED_PERCENT, AlarmConditionOperator.GTE, 90, AlarmLevel.WARNING, 0),
                policy("node-fs", "ADMIN", null, "node", AlarmTargetScope.INDIVIDUAL, Set.of("2"),
                        AlarmMetric.FS_USED_PERCENT, AlarmConditionOperator.GTE, 90, AlarmLevel.WARNING, 0)));
        when(vmClient.query(anyString())).thenReturn(vector(
                sample(Map.of("instance", "192.168.10.5:9100", "tb_ns", "openstack", "tb_infra", "infra-01", "tb_node", "vm-1"), 93.0),
                sample(Map.of("instance", "192.168.10.6:9100", "tb_ns", "openstack", "tb_infra", "infra-01", "tb_node", "vm-2"), 99.0),
                sample(Map.of("instance", "10.0.0.1:9100", "mdc_id", "mdc-01"), 99.0),      // asset 1 — node-fs 개별 대상(2) 아님
                sample(Map.of("instance", "10.0.0.2:9100", "mdc_id", "mdc-02"), 95.0)));

        service.evaluatePolicies();

        ArgumentCaptor<AlarmHistory> saved = ArgumentCaptor.forClass(AlarmHistory.class);
        verify(historyRepository, times(2)).save(saved.capture());
        AlarmHistory vm = saved.getAllValues().stream().filter(h -> "vm".equals(h.getResourceType())).findFirst().orElseThrow();
        assertThat(vm.getResourceId()).isEqualTo("TB:openstack/infra-01/vm-1");
        assertThat(vm.getResourceName()).isEqualTo("vm-1");
        assertThat(vm.getAreaId()).isEqualTo("TB:openstack");
        assertThat(vm.getAreaKind()).isEqualTo(AreaKind.NAMESPACE);
        assertThat(vm.getCriterion()).isEqualTo("FS Used >= 90%");
        AlarmHistory node = saved.getAllValues().stream().filter(h -> "node".equals(h.getResourceType())).findFirst().orElseThrow();
        assertThat(node.getResourceId()).isEqualTo("2");
    }

    @Test
    void 첫_발생은_지문을_남기고_같은_지문의_미해소_행이_있으면_다시_만들지_않는다() {
        AlarmPolicy p = nodeAll("cpu-80", AlarmMetric.CPU_USED_PERCENT, 80);
        when(policyRepository.findByEnabledTrueAndType(AlarmPolicyType.METRIC)).thenReturn(List.of(p));
        when(vmClient.query(anyString())).thenReturn(vector(sample(Map.of("instance", "10.0.0.1:9100", "mdc_id", "mdc-01"), 92.0)));

        service.evaluatePolicies();
        ArgumentCaptor<AlarmHistory> saved = ArgumentCaptor.forClass(AlarmHistory.class);
        verify(historyRepository, times(1)).save(saved.capture());
        AlarmHistory first = saved.getValue();
        assertThat(first.getFingerprint()).isEqualTo(AlarmEvaluationService.fingerprint(p, AlarmMetric.CPU_USED_PERCENT, "1"));

        when(historyRepository.findBySourceAndMetricAndStatusNot(AlarmSource.POLICY, "CPU_USED_PERCENT", AlarmHistoryStatus.RESOLVED))
                .thenReturn(List.of(first));
        service.evaluatePolicies();
        verify(historyRepository, times(1)).save(any());
        verify(eventPublisher, times(1)).publishEvent(any(AlarmStateChangedEvent.class));
        assertThat(first.getStatus()).isEqualTo(AlarmHistoryStatus.OPEN);
    }

    @Test
    void 확인된_행도_억제하고_임계값_안으로_돌아오면_해소하며_해소_이벤트를_발행한다() {
        AlarmPolicy p = nodeAll("mem-80", AlarmMetric.MEMORY_USED_PERCENT, 80);
        when(policyRepository.findByEnabledTrueAndType(AlarmPolicyType.METRIC)).thenReturn(List.of(p));
        String fp = AlarmEvaluationService.fingerprint(p, AlarmMetric.MEMORY_USED_PERCENT, "1");
        AlarmHistory acked = AlarmHistory.builder(AlarmSource.POLICY, AlarmLevel.WARNING, "Memory Used > 80%")
                .policy(null, "mem-80").resource("node", "1", "node-01").role("ADMIN").mdc("mdc-01")
                .metric("MEMORY_USED_PERCENT").observed(91.0, "%").fingerprint(fp).build();
        acked.acknowledge("admin@innogrid.com", "확인", OffsetDateTime.now());
        when(historyRepository.findBySourceAndMetricAndStatusNot(AlarmSource.POLICY, "MEMORY_USED_PERCENT", AlarmHistoryStatus.RESOLVED))
                .thenReturn(List.of(acked));

        when(vmClient.query(anyString())).thenReturn(vector(sample(Map.of("instance", "10.0.0.1:9100", "mdc_id", "mdc-01"), 93.0)));
        service.evaluatePolicies();
        verify(historyRepository, never()).save(any());
        assertThat(acked.getStatus()).isEqualTo(AlarmHistoryStatus.ACKNOWLEDGED);

        when(vmClient.query(anyString())).thenReturn(vector(sample(Map.of("instance", "10.0.0.1:9100", "mdc_id", "mdc-01"), 62.5)));
        service.evaluatePolicies();

        assertThat(acked.getStatus()).isEqualTo(AlarmHistoryStatus.RESOLVED);
        assertThat(acked.getResolvedAt()).isNotNull();
        assertThat(acked.getAcknowledgedBy()).isEqualTo("admin@innogrid.com");
        verify(historyRepository, times(1)).save(acked);
        ArgumentCaptor<AlarmStateChangedEvent> event = ArgumentCaptor.forClass(AlarmStateChangedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(event.capture());
        AlarmStateChangedEvent resolved = event.getValue();
        assertThat(resolved.transition()).isEqualTo(Transition.RESOLVED);
        assertThat(resolved.policyName()).isEqualTo("mem-80");
        assertThat(resolved.criterion()).isEqualTo("Memory Used > 80%");
        assertThat(resolved.observedValue()).isEqualTo(62.5);
        assertThat(resolved.unit()).isEqualTo("%");
        assertThat(resolved.resourceId()).isEqualTo("1");
        assertThat(resolved.mdcId()).isEqualTo("mdc-01");
        assertThat(resolved.occurredAt()).isEqualTo(acked.getResolvedAt());
    }

    @Test
    void 지속_시간이_있으면_처음_초과에서는_발생시키지_않고_초과가_풀리면_대기를_지운다() {
        AlarmPolicy p = policy("cpu-5m", "ADMIN", null, "node", AlarmTargetScope.ALL, Set.of(), AlarmMetric.CPU_USED_PERCENT,
                AlarmConditionOperator.GT, 80, AlarmLevel.WARNING, 300);
        when(policyRepository.findByEnabledTrueAndType(AlarmPolicyType.METRIC)).thenReturn(List.of(p));
        when(vmClient.query(anyString())).thenReturn(vector(sample(Map.of("instance", "10.0.0.1:9100", "mdc_id", "mdc-01"), 92.0)));

        service.evaluatePolicies();
        service.evaluatePolicies();                                     // 5분이 지나지 않음

        verify(historyRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void 표본이_없는_자원은_열어_두고_스코프_조회가_실패하면_평가하지_않는다() {
        AlarmPolicy p = nodeAll("cpu-80", AlarmMetric.CPU_USED_PERCENT, 80);
        when(policyRepository.findByEnabledTrueAndType(AlarmPolicyType.METRIC)).thenReturn(List.of(p));
        AlarmHistory open = AlarmHistory.builder(AlarmSource.POLICY, AlarmLevel.WARNING, "CPU Used > 80%")
                .policy(null, "cpu-80").resource("node", "2", "node-02").metric("CPU_USED_PERCENT")
                .fingerprint(AlarmEvaluationService.fingerprint(p, AlarmMetric.CPU_USED_PERCENT, "2")).build();
        when(historyRepository.findBySourceAndMetricAndStatusNot(AlarmSource.POLICY, "CPU_USED_PERCENT", AlarmHistoryStatus.RESOLVED))
                .thenReturn(List.of(open));
        when(vmClient.query(anyString())).thenReturn(vector(sample(Map.of("instance", "10.0.0.1:9100", "mdc_id", "mdc-01"), 10.0)));

        service.evaluatePolicies();
        assertThat(open.getStatus()).isEqualTo(AlarmHistoryStatus.OPEN);
        verify(historyRepository, never()).save(any());

        when(resourceScopeService.scopeOf(anyCollection())).thenThrow(new IllegalStateException("tumblebug down"));
        service.evaluatePolicies();
        verify(vmClient, times(1)).query(anyString());                  // 두 번째 주기는 쿼리하지 않음
    }

    @Test
    void 한_메트릭의_Thanos_장애는_다른_메트릭_평가를_막지_않고_NaN_표본은_판정하지_않는다() {
        when(policyRepository.findByEnabledTrueAndType(AlarmPolicyType.METRIC)).thenReturn(List.of(
                nodeAll("cpu", AlarmMetric.CPU_USED_PERCENT, 80),
                policy("gpu-temp", "ADMIN", null, "gpu", AlarmTargetScope.ALL, Set.of(), AlarmMetric.GPU_TEMP,
                        AlarmConditionOperator.GT, 85, AlarmLevel.CRITICAL, 0)));
        when(vmClient.query(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.contains("node_cpu_seconds_total")) throw new IllegalStateException("thanos down");
            return vector(sample(Map.of("instance", "10.0.0.1:9100", "mdc_id", "mdc-01"), 88.0),
                    sample(Map.of("instance", "10.0.0.2:9100", "mdc_id", "mdc-02"), "NaN"));
        });

        service.evaluatePolicies();

        verify(vmClient, times(2)).query(anyString());
        ArgumentCaptor<AlarmHistory> saved = ArgumentCaptor.forClass(AlarmHistory.class);
        verify(historyRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getMetric()).isEqualTo("GPU_TEMP");
        assertThat(saved.getValue().getResourceType()).isEqualTo("gpu");
        assertThat(saved.getValue().getCriterion()).isEqualTo("GPU Temperature > 85°C");
    }

    @Test
    void 활성_정책이_없으면_스코프도_쿼리도_읽지_않는다() {
        when(policyRepository.findByEnabledTrueAndType(AlarmPolicyType.METRIC)).thenReturn(List.of());

        service.evaluatePolicies();

        verify(resourceScopeService, never()).scopeOf(anyCollection());
        verify(vmClient, never()).query(anyString());
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
