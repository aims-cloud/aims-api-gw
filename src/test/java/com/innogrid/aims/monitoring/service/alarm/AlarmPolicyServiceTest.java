package com.innogrid.aims.monitoring.service.alarm;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.innogrid.aims.auth.entity.User;
import com.innogrid.aims.auth.repository.UserRepository;
import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmChannel;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmConditionOperator;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyDetailResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyListItemResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyRecipientResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyRequest;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyRequest.Condition;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyRequest.Delivery;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyRequest.Recipient;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyResourceResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyType;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmRecipientCandidatesResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmTargetScope;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmTargetType;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleAreaListItemResponse.AreaKind;
import com.innogrid.aims.monitoring.log.PortalLogContext;
import com.innogrid.aims.monitoring.log.PortalLogContextResolver;
import com.innogrid.aims.monitoring.repository.AlarmPolicyRepository;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedVm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * 알람 정책 서비스 — 생성(검증·생성자 표시명·기준 문자열), 400 규칙(역할·메트릭·개별 대상·수신자·LOG·채널), 목록(역할 스코프·필터·
 * 정렬·대상 자원 수·resourceId), 상세 404, 편집, 복제, 다중 삭제, 대상 자원 탭, 전송 대상 탭(삭제된 사용자), 수신자 후보.
 * 원장·users 는 실제 H2, 인증·역할 스코프·요청 문맥은 대역. 토큰 역할은 ADMIN 하나.
 */
@SpringBootTest
@Transactional
class AlarmPolicyServiceTest {

    @Autowired AlarmPolicyRepository repository;
    @Autowired UserRepository userRepository;
    @MockitoBean AuthService authService;
    @MockitoBean ResourceScopeService resourceScopeService;
    @MockitoBean PortalLogContextResolver contextResolver;
    @Autowired AlarmPolicyService service;

    Long adminId;
    Long opsId;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        adminId = userRepository.save(new User("admin-p@innogrid.com", "pw", "관리자", "플랫폼팀")).getId();
        opsId = userRepository.save(new User("ops-p@innogrid.com", "pw", "운영자", "운영팀")).getId();
        userRepository.save(new User("noorg-p@innogrid.com", "pw", "무소속", null));

        when(authService.currentUserRoleIds()).thenReturn(new LinkedHashSet<>(List.of("ADMIN")));
        when(contextResolver.resolve()).thenReturn(new PortalLogContext("admin-p@innogrid.com", "관리자", "10.0.0.9", "req", "POST", "/x"));
        when(resourceScopeService.scopeOf(anyCollection())).thenReturn(new Scope(Set.of("ADMIN", "USER"),
                List.of(new ScopedHost("ADMIN", 1L, "node-01", "alpha", "mdc-01", null, "10.0.0.1:9100", List.of()),
                        new ScopedHost("ADMIN", 2L, "node-02", "beta", "mdc-01", null, "10.0.0.2:9100", List.of()),
                        new ScopedHost("ADMIN", 3L, "node-03", null, "mdc-02", null, "10.0.0.3:9100", List.of()),
                        new ScopedHost("USER", 4L, "node-04", null, "mdc-01", null, "10.0.0.4:9100", List.of())),
                List.of(new ScopedVm("ADMIN", "TB:openstack/infra-01/vm-1", "vm-1", null, null, "openstack")),
                List.of()));
    }

    AlarmPolicyRequest request(String name, String role, String areaId, String resourceType, AlarmTargetScope scope,
                               List<String> targets, String metric, AlarmConditionOperator op, double threshold,
                               List<Recipient> recipients) {
        return new AlarmPolicyRequest(AlarmPolicyType.METRIC, name, role, areaId, resourceType, scope, targets, "설명",
                new Condition("CPU", metric, op, threshold, AlarmLevel.WARNING, null, null),
                new Delivery(0, List.of(AlarmChannel.EMAIL, AlarmChannel.SMS), recipients));
    }

    AlarmPolicyRequest cpuAll(String name) {
        return request(name, "ADMIN", "mdc-01", "node", AlarmTargetScope.ALL, List.of(), "CPU_USED_PERCENT",
                AlarmConditionOperator.GT, 80, List.of(new Recipient(AlarmTargetType.INDIVIDUAL, String.valueOf(opsId)),
                        new Recipient(AlarmTargetType.ORGANIZATION, "플랫폼팀")));
    }

    // ── 생성 ─────────────────────────────────────────────────────

    @Test
    void 생성하면_검증된_값과_생성자_표시명_기준_문자열_수신자_이름이_상세로_돌아온다() {
        AlarmPolicyDetailResponse d = service.createPolicy(cpuAll("cpu-high"));

        assertThat(d.policyId()).isNotNull();
        assertThat(d.alarmType()).isEqualTo(AlarmPolicyType.METRIC);
        assertThat(d.roleName()).isEqualTo("ADMIN");
        assertThat(d.areaId()).isEqualTo("mdc-01");
        assertThat(d.areaKind()).isEqualTo(AreaKind.MDC);
        assertThat(d.resourceType()).isEqualTo("node");
        assertThat(d.targetScope()).isEqualTo(AlarmTargetScope.ALL);
        assertThat(d.metricType()).isEqualTo("CPU");
        assertThat(d.metric()).isEqualTo("CPU_USED_PERCENT");
        assertThat(d.criterion()).isEqualTo("CPU Used > 80%");
        assertThat(d.channels()).containsExactly(AlarmChannel.EMAIL, AlarmChannel.SMS);
        assertThat(d.createdBy()).isEqualTo("관리자");
        assertThat(d.recipients()).extracting(AlarmPolicyRecipientResponse::targetType, AlarmPolicyRecipientResponse::name)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(AlarmTargetType.INDIVIDUAL, "운영자"),
                        org.assertj.core.groups.Tuple.tuple(AlarmTargetType.ORGANIZATION, "플랫폼팀"));
        assertThat(d.enabled()).isTrue();
        assertThat(d.createdAt()).isNotNull();

        assertThat(repository.findById(d.policyId()).orElseThrow().getCreatedBy()).isEqualTo("admin-p@innogrid.com");
    }

    @Test
    void 초안_별칭_메트릭과_Namespace_영역도_받고_개별_대상은_id_를_저장한다() {
        AlarmPolicyDetailResponse d = service.createPolicy(request("vm-mem", "ADMIN", "TB:openstack", "vm", AlarmTargetScope.INDIVIDUAL,
                List.of("TB:openstack/infra-01/vm-1", " TB:openstack/infra-01/vm-1 "), "mem_usage", AlarmConditionOperator.GTE, 70.5,
                List.of(new Recipient(AlarmTargetType.INDIVIDUAL, String.valueOf(adminId)))));

        assertThat(d.areaKind()).isEqualTo(AreaKind.NAMESPACE);
        assertThat(d.metric()).isEqualTo("MEMORY_USED_PERCENT");
        assertThat(d.metricType()).isEqualTo("Memory");
        assertThat(d.criterion()).isEqualTo("Memory Used >= 70.5%");
        assertThat(d.targetResourceIds()).containsExactly("TB:openstack/infra-01/vm-1");
    }

    @Test
    void 검증_400_역할_메트릭_개별대상_수신자_채널_LOG() {
        AlarmPolicyRequest base = cpuAll("x");
        assertBadRequest(request("x", "USER", null, "node", AlarmTargetScope.ALL, List.of(), "CPU_USED_PERCENT",
                AlarmConditionOperator.GT, 80, base.delivery().recipients()), "없는 역할");
        assertBadRequest(request("x", "ADMIN", null, "vm", AlarmTargetScope.ALL, List.of(), "GPU_UTIL",
                AlarmConditionOperator.GT, 80, base.delivery().recipients()), "쓸 수 없습니다");
        assertBadRequest(request("x", "ADMIN", null, "node", AlarmTargetScope.ALL, List.of(), "node_load1",
                AlarmConditionOperator.GT, 80, base.delivery().recipients()), "카탈로그에 없는");
        assertBadRequest(request("x", "ADMIN", null, "node", AlarmTargetScope.INDIVIDUAL, List.of(), "CPU_USED_PERCENT",
                AlarmConditionOperator.GT, 80, base.delivery().recipients()), "1개 이상 선택");
        assertBadRequest(request("x", "ADMIN", null, "node", AlarmTargetScope.ALL, List.of(), "CPU_USED_PERCENT",
                AlarmConditionOperator.GT, 80, List.of(new Recipient(AlarmTargetType.INDIVIDUAL, "999999"))), "존재하지 않는 사용자");
        assertBadRequest(request("x", "ADMIN", null, "node", AlarmTargetScope.ALL, List.of(), "CPU_USED_PERCENT",
                AlarmConditionOperator.GT, 80, List.of(new Recipient(AlarmTargetType.ORGANIZATION, "없는팀"))), "존재하지 않는 조직");
        assertBadRequest(request("x", "ADMIN", null, "node", AlarmTargetScope.ALL, List.of(), "CPU_USED_PERCENT",
                AlarmConditionOperator.GT, 80, List.of()), "수신자를 1명 이상");
        assertBadRequest(request("x", "ADMIN", null, "sw", AlarmTargetScope.ALL, List.of(), "CPU_USED_PERCENT",
                AlarmConditionOperator.GT, 80, base.delivery().recipients()), "지원하지 않는 자원 유형");
        assertBadRequest(new AlarmPolicyRequest(AlarmPolicyType.METRIC, "x", "ADMIN", null, "node", AlarmTargetScope.ALL, List.of(), null,
                base.condition(), new Delivery(0, List.of(), base.delivery().recipients())), "알림 방식");
        assertBadRequest(new AlarmPolicyRequest(AlarmPolicyType.LOG, "x", "ADMIN", null, "node", AlarmTargetScope.ALL, List.of(), null,
                base.condition(), base.delivery()), "로그 알람");
        assertThat(repository.count()).isZero();
    }

    private void assertBadRequest(AlarmPolicyRequest req, String message) {
        assertThatThrownBy(() -> service.createPolicy(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(message)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── 목록·상세 ─────────────────────────────────────────────────

    @Test
    void 목록은_토큰_역할_정책만_보이고_대상_자원_수는_역할_스코프에서_전개한다() {
        service.createPolicy(cpuAll("b-policy"));                                                   // ALL, mdc-01 → asset 1,2
        service.createPolicy(request("a-policy", "ADMIN", null, "node", AlarmTargetScope.INDIVIDUAL, List.of("3", "999"),
                "FS_USED_PERCENT", AlarmConditionOperator.GT, 90, cpuAll("x").delivery().recipients()));  // 999 는 스코프에 없음 → 1
        // USER 역할 정책 — 토큰에 없는 역할이라 보이지 않아야 한다 (검증을 우회해 직접 저장)
        when(authService.currentUserRoleIds()).thenReturn(new LinkedHashSet<>(List.of("ADMIN", "USER")));
        service.createPolicy(request("user-policy", "USER", null, "node", AlarmTargetScope.ALL, List.of(), "CPU_USED_PERCENT",
                AlarmConditionOperator.GT, 50, cpuAll("x").delivery().recipients()));
        when(authService.currentUserRoleIds()).thenReturn(new LinkedHashSet<>(List.of("ADMIN")));

        PageResponse<AlarmPolicyListItemResponse> page = service.getPolicies(null, null, null, null, null, null, 1, 10, "policyName,asc");

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.items()).extracting(AlarmPolicyListItemResponse::policyName).containsExactly("a-policy", "b-policy");
        assertThat(page.items()).extracting(AlarmPolicyListItemResponse::targetResourceCount).containsExactly(1L, 2L);
        assertThat(page.items().get(1).criterion()).isEqualTo("CPU Used > 80%");
        assertThat(page.items().get(1).createdBy()).isEqualTo("관리자");

        assertThat(service.getPolicies("USER", null, null, null, null, null, 1, 10, null).items()).isEmpty();
        assertThat(service.getPolicies(null, "mdc-01", null, null, null, "B-POL", 1, 10, null).items())
                .extracting(AlarmPolicyListItemResponse::policyName).containsExactly("b-policy");
        assertThat(service.getPolicies(null, null, null, null, AlarmLevel.CRITICAL, null, 1, 10, null).items()).isEmpty();
        assertThatThrownBy(() -> service.getPolicies(null, null, null, null, null, null, 1, 10, "targetResourceCount,asc"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void resourceId_필터는_개별_대상에_포함된_정책과_실제로_그_자원을_덮는_ALL_정책만_남긴다() {
        service.createPolicy(cpuAll("all-mdc-01"));                                                 // asset 1,2 덮음
        service.createPolicy(request("all-mdc-02", "ADMIN", "mdc-02", "node", AlarmTargetScope.ALL, List.of(), "CPU_USED_PERCENT",
                AlarmConditionOperator.GT, 80, cpuAll("x").delivery().recipients()));                // asset 3 만
        service.createPolicy(request("pick-1", "ADMIN", null, "node", AlarmTargetScope.INDIVIDUAL, List.of("1"), "CPU_USED_PERCENT",
                AlarmConditionOperator.GT, 80, cpuAll("x").delivery().recipients()));

        List<AlarmPolicyListItemResponse> items = service.getPolicies(null, null, null, "1", null, null, 1, 10, "policyName,asc").items();

        assertThat(items).extracting(AlarmPolicyListItemResponse::policyName).containsExactly("all-mdc-01", "pick-1");
    }

    @Test
    void 스코프_밖_정책의_상세_편집_삭제는_404이고_삭제는_하나라도_없으면_전체_거부한다() {
        Long mine = service.createPolicy(cpuAll("mine")).policyId();
        when(authService.currentUserRoleIds()).thenReturn(new LinkedHashSet<>(List.of("ADMIN", "USER")));
        Long theirs = service.createPolicy(request("theirs", "USER", null, "node", AlarmTargetScope.ALL, List.of(), "CPU_USED_PERCENT",
                AlarmConditionOperator.GT, 50, cpuAll("x").delivery().recipients())).policyId();
        when(authService.currentUserRoleIds()).thenReturn(new LinkedHashSet<>(List.of("ADMIN")));

        assertNotFound(() -> service.getPolicy(theirs));
        assertNotFound(() -> service.updatePolicy(theirs, cpuAll("y")));
        assertNotFound(() -> service.deletePolicies(List.of(mine, theirs)));
        assertNotFound(() -> service.getPolicy(999_999L));
        assertThat(repository.count()).isEqualTo(2);

        service.deletePolicies(List.of(mine));
        assertThat(repository.existsById(mine)).isFalse();
        assertThat(repository.existsById(theirs)).isTrue();
    }

    private void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── 편집·복제 ────────────────────────────────────────────────

    @Test
    void 편집은_전체_값을_바꾸고_복제는_같은_설정에_복제_이름과_복제자를_붙인다() {
        Long id = service.createPolicy(cpuAll("cpu-high")).policyId();

        AlarmPolicyDetailResponse edited = service.updatePolicy(id, request("cpu-crit", "ADMIN", null, "node", AlarmTargetScope.INDIVIDUAL,
                List.of("1", "2"), "CPU_USED_PERCENT", AlarmConditionOperator.GTE, 95,
                List.of(new Recipient(AlarmTargetType.ORGANIZATION, "운영팀"))));
        assertThat(edited.policyId()).isEqualTo(id);
        assertThat(edited.policyName()).isEqualTo("cpu-crit");
        assertThat(edited.areaId()).isNull();
        assertThat(edited.areaKind()).isNull();
        assertThat(edited.targetScope()).isEqualTo(AlarmTargetScope.INDIVIDUAL);
        assertThat(edited.targetResourceIds()).containsExactly("1", "2");
        assertThat(edited.criterion()).isEqualTo("CPU Used >= 95%");
        assertThat(edited.recipients()).extracting(AlarmPolicyRecipientResponse::name).containsExactly("운영팀");

        when(contextResolver.resolve()).thenReturn(new PortalLogContext("ops-p@innogrid.com", "운영자", null, null, null, null));
        AlarmPolicyDetailResponse copy = service.duplicatePolicy(id);
        assertThat(copy.policyId()).isNotEqualTo(id);
        assertThat(copy.policyName()).isEqualTo("cpu-crit (복제)");
        assertThat(copy.createdBy()).isEqualTo("운영자");
        assertThat(copy.targetResourceIds()).containsExactly("1", "2");
        assertThat(copy.criterion()).isEqualTo("CPU Used >= 95%");
        assertThat(repository.count()).isEqualTo(2);
    }

    // ── 하단 탭 ──────────────────────────────────────────────────

    @Test
    void 대상_자원_탭은_전개한_자원을_키워드_정렬_페이징하고_전송_대상_탭은_삭제된_사용자를_표시한다() {
        Long id = service.createPolicy(cpuAll("cpu-high")).policyId();

        PageResponse<AlarmPolicyResourceResponse> all = service.getPolicyResources(id, null, 1, 10, "name,desc");
        assertThat(all.items()).extracting(AlarmPolicyResourceResponse::name).containsExactly("node-02", "node-01");
        assertThat(all.items().get(1).alias()).isEqualTo("alpha");
        assertThat(all.items().get(1).resourceId()).isEqualTo("1");
        assertThat(service.getPolicyResources(id, "BETA", 1, 10, null).items())
                .extracting(AlarmPolicyResourceResponse::name).containsExactly("node-02");
        assertThat(service.getPolicyResources(id, null, 2, 1, null).items()).hasSize(1);

        userRepository.deleteById(opsId);
        List<AlarmPolicyRecipientResponse> recipients = service.getPolicyRecipients(id);
        assertThat(recipients).extracting(AlarmPolicyRecipientResponse::name)
                .containsExactly(AlarmPolicyService.DELETED_USER, "플랫폼팀");
    }

    @Test
    void 수신자_후보는_조직_문자열로_묶고_무소속은_미지정이며_키워드는_조직과_사용자를_찾는다() {
        AlarmRecipientCandidatesResponse all = service.getRecipientCandidates(null);
        assertThat(all.organizations()).extracting(AlarmRecipientCandidatesResponse.OrgNode::orgId)
                .contains("플랫폼팀", "운영팀", AlarmPolicyService.UNASSIGNED_ORG);
        AlarmRecipientCandidatesResponse.OrgNode platform = all.organizations().stream()
                .filter(o -> o.orgId().equals("플랫폼팀")).findFirst().orElseThrow();
        assertThat(platform.members()).extracting(AlarmRecipientCandidatesResponse.Member::userId, AlarmRecipientCandidatesResponse.Member::name)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(String.valueOf(adminId), "관리자"));
        assertThat(platform.children()).isEmpty();

        assertThat(service.getRecipientCandidates("운영").organizations())
                .extracting(AlarmRecipientCandidatesResponse.OrgNode::orgId).containsExactly("운영팀");
        assertThat(service.getRecipientCandidates("무소속").organizations())
                .extracting(AlarmRecipientCandidatesResponse.OrgNode::orgId).containsExactly(AlarmPolicyService.UNASSIGNED_ORG);
        assertThat(service.getRecipientCandidates("없는이름").organizations()).isEmpty();
    }
}
