package com.innogrid.aims.monitoring.repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmHistorySearchField;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmHistoryStatus;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmSource;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;
import com.innogrid.aims.monitoring.entity.AlarmHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알람 발생 이력 원장 — 레벨(다중)·역할·배포영역·자원 유형·자원·정책·기간·키워드 필터, 역할 스코프(역할 없는 행 공개),
 * 정렬·페이징, 확인·해소 전이, 외부 알람 fingerprint 조회.
 *
 * 대역 (KST 2026-09-08):
 *   09:00 POLICY  CRITICAL node-01 "CPU Used > 80%" 92%  ADMIN/mdc-01 policy 1 cpu-high
 *   09:30 POLICY  WARNING  vm-1    "Memory Used > 70%" 75.5% USER/openstack policy 2 mem-high
 *   10:00 SYSTEM  WARNING  node-02 "up == 0"  role 없음 mdc-01
 *   11:00 EXTERNAL INFO    (자원 없음) "KETI 보안 이벤트" fingerprint fp-1, role 없음
 *   12:00 POLICY  CRITICAL node-01 "CPU Used > 80%" 95% ADMIN/mdc-01 policy 1 — 확인 완료
 */
@SpringBootTest
@Transactional
class AlarmHistoryRepositoryTest {

    @Autowired
    AlarmHistoryRepository repository;

    static final OffsetDateTime BASE = OffsetDateTime.of(2026, 9, 8, 9, 0, 0, 0, ZoneOffset.ofHours(9));
    static final Set<String> ADMIN_ONLY = Set.of("ADMIN");

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        repository.save(AlarmHistory.builder(AlarmSource.POLICY, AlarmLevel.CRITICAL, "CPU Used > 80%")
                .policy(1L, "cpu-high").resource("node", "asset-1", "node-01").role("ADMIN").mdc("mdc-01")
                .metric("CPU_USED_PERCENT").observed(92.0, "%").occurredAt(BASE).build());
        repository.save(AlarmHistory.builder(AlarmSource.POLICY, AlarmLevel.WARNING, "Memory Used > 70%")
                .policy(2L, "mem-high").resource("vm", "TB:openstack/openstack-infra-01/vm-1", "vm-1").role("USER").namespace("openstack")
                .metric("MEMORY_USED_PERCENT").observed(75.5, "%").occurredAt(BASE.plusMinutes(30)).build());
        repository.save(AlarmHistory.builder(AlarmSource.SYSTEM, AlarmLevel.WARNING, "up == 0")
                .resource("node", "asset-2", "node-02").mdc("mdc-01").occurredAt(BASE.plusHours(1)).build());
        repository.save(AlarmHistory.builder(AlarmSource.EXTERNAL, AlarmLevel.INFO, "KETI 보안 이벤트")
                .fingerprint("fp-1").labelsJson("{\"alertname\":\"SecurityEvent\"}").occurredAt(BASE.plusHours(2)).build());
        AlarmHistory acked = repository.save(AlarmHistory.builder(AlarmSource.POLICY, AlarmLevel.CRITICAL, "CPU Used > 80%")
                .policy(1L, "cpu-high").resource("node", "asset-1", "node-01").role("ADMIN").mdc("mdc-01")
                .metric("CPU_USED_PERCENT").observed(95.0, "%").occurredAt(BASE.plusHours(3)).build());
        acked.acknowledge("admin@innogrid.com", "재기동 예정", BASE.plusHours(3).plusMinutes(5));
    }

    @Test
    void 필터_없이_스코프_없으면_전체가_발생_시각_내림차순() {
        Page<AlarmHistory> page = repository.findAll(filter(null, null, null, null, null, null, null, null, null, null),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "occurredAt")));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent().get(0).getObservedValue()).isEqualTo(95.0);
        assertThat(page.getContent().get(0).isAcknowledged()).isTrue();
        assertThat(page.getContent().get(0).getStatus()).isEqualTo(AlarmHistoryStatus.ACKNOWLEDGED);
    }

    @Test
    void 역할_스코프는_토큰_역할_행과_역할_없는_행만_보인다() {
        List<AlarmHistory> admin = repository.findAll(filter(null, null, null, null, null, null, null, null, null, ADMIN_ONLY));
        assertThat(admin).extracting(AlarmHistory::getSource)
                .containsExactlyInAnyOrder(AlarmSource.POLICY, AlarmSource.POLICY, AlarmSource.SYSTEM, AlarmSource.EXTERNAL);
        assertThat(admin).extracting(AlarmHistory::getRoleName).doesNotContain("USER");

        List<AlarmHistory> noRoles = repository.findAll(filter(null, null, null, null, null, null, null, null, null, Set.of()));
        assertThat(noRoles).hasSize(2);                                            // 역할 없는 SYSTEM·EXTERNAL 만
    }

    @Test
    void 레벨_다중_역할_자원영역_자원유형_자원_정책_필터() {
        assertThat(repository.findAll(filter(List.of(AlarmLevel.CRITICAL, AlarmLevel.INFO), null, null, null, null, null, null, null, null, null)))
                .hasSize(3);
        assertThat(repository.findAll(filter(null, "USER", null, null, null, null, null, null, null, null))).hasSize(1);
        assertThat(repository.findAll(filter(null, null, "mdc-01", null, null, null, null, null, null, null))).hasSize(3);
        assertThat(repository.findAll(filter(null, null, "TB:openstack", null, null, null, null, null, null, null))).hasSize(1);
        assertThat(repository.findAll(filter(null, null, null, "VM", null, null, null, null, null, null))).hasSize(1);   // 대소문자 무시
        assertThat(repository.findAll(filter(null, null, null, null, "asset-1", null, null, null, null, null))).hasSize(2);
        assertThat(repository.findAll(filter(null, null, null, null, null, 1L, null, null, null, null))).hasSize(2);
    }

    @Test
    void 키워드는_검색_대상_컬럼에서_찾고_기간은_시작_포함_끝_미포함() {
        assertThat(repository.findAll(filter(null, null, null, null, null, null, "NODE-01", null, null, null))).hasSize(2);
        assertThat(repository.findAll(filter(null, null, null, null, null, null, "mem-high", null, null, null))).hasSize(1);
        assertThat(repository.findAll(filter(null, null, null, null, null, null, "up ==", null, null, null))).isEmpty();  // 기준은 검색 대상 아님
        assertThat(repository.findAll(AlarmHistorySpecifications.filter(null, null, null, null, null, null,
                AlarmHistorySearchField.RESOURCE_NAME, "cpu-high", null, null, null))).isEmpty();                     // 자원명만
        assertThat(repository.findAll(AlarmHistorySpecifications.filter(null, null, null, null, null, null,
                AlarmHistorySearchField.POLICY_NAME, "cpu-high", null, null, null))).hasSize(2);                      // 정책명만
        assertThat(repository.findAll(filter(null, null, null, null, null, null, "100%", null, null, null))).isEmpty(); // % 리터럴

        assertThat(repository.findAll(filter(null, null, null, null, null, null, null, BASE.plusMinutes(30), BASE.plusHours(2), null)))
                .extracting(AlarmHistory::getSource).containsExactlyInAnyOrder(AlarmSource.POLICY, AlarmSource.SYSTEM);
    }

    @Test
    void 해소_전이와_fingerprint_미해소_조회() {
        AlarmHistory external = repository.findFirstByFingerprintAndStatusNotOrderByOccurredAtDesc("fp-1", AlarmHistoryStatus.RESOLVED)
                .orElseThrow();
        external.resolve(BASE.plusHours(2).plusMinutes(10));
        repository.saveAndFlush(external);

        assertThat(repository.findFirstByFingerprintAndStatusNotOrderByOccurredAtDesc("fp-1", AlarmHistoryStatus.RESOLVED)).isEmpty();
        assertThat(repository.findById(external.getId()).orElseThrow().getResolvedAt()).isNotNull();
    }

    private static org.springframework.data.jpa.domain.Specification<AlarmHistory> filter(
            List<AlarmLevel> levels, String roleName, String areaId, String resourceType, String resourceId,
            Long policyId, String keyword, OffsetDateTime from, OffsetDateTime to, Set<String> visibleRoles) {
        return AlarmHistorySpecifications.filter(levels, roleName, areaId, resourceType, resourceId, policyId,
                AlarmHistorySearchField.ALL, keyword, from, to, visibleRoles);
    }

    @Test
    void 메트릭별_미해소_정책_알람_조회는_OPEN과_ACKNOWLEDGED만_돌려주고_RESOLVED와_다른_source는_뺀다() {
        AlarmHistory open = repository.save(AlarmHistory.builder(AlarmSource.POLICY, AlarmLevel.WARNING, "CPU Used > 80%")
                .policy(1L, "cpu-high").resource("node", "asset-3", "node-03").metric("CPU_USED_PERCENT")
                .fingerprint("fp-open").occurredAt(BASE.plusHours(4)).build());
        AlarmHistory resolved = repository.save(AlarmHistory.builder(AlarmSource.POLICY, AlarmLevel.WARNING, "CPU Used > 80%")
                .policy(1L, "cpu-high").resource("node", "asset-4", "node-04").metric("CPU_USED_PERCENT")
                .fingerprint("fp-resolved").occurredAt(BASE.plusHours(4)).build());
        resolved.resolve(BASE.plusHours(5));
        repository.save(AlarmHistory.builder(AlarmSource.SYSTEM, AlarmLevel.WARNING, "CPU Used > 80%")
                .resource("node", "asset-5", "node-05").metric("CPU_USED_PERCENT").occurredAt(BASE.plusHours(4)).build());

        List<AlarmHistory> active = repository.findBySourceAndMetricAndStatusNot(
                AlarmSource.POLICY, "CPU_USED_PERCENT", AlarmHistoryStatus.RESOLVED);

        // 대역의 CPU_USED_PERCENT 정책 행 2건(OPEN 09:00, ACKNOWLEDGED 12:00) + 여기서 만든 OPEN 1건
        assertThat(active).extracting(AlarmHistory::getResourceName)
                .containsExactlyInAnyOrder("node-01", "node-01", "node-03");
        assertThat(active).extracting(AlarmHistory::getStatus)
                .containsOnly(AlarmHistoryStatus.OPEN, AlarmHistoryStatus.ACKNOWLEDGED);
        assertThat(active).extracting(AlarmHistory::getId).doesNotContain(resolved.getId()).contains(open.getId());
        assertThat(repository.findBySourceAndMetricAndStatusNot(AlarmSource.POLICY, "GPU_TEMP", AlarmHistoryStatus.RESOLVED)).isEmpty();
    }
}
