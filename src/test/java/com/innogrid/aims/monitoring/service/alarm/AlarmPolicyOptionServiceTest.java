package com.innogrid.aims.monitoring.service.alarm;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.repository.RoleRepository;
import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmAreaCandidateResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmRoleCandidateResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleAreaListItemResponse.AreaKind;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedNamespace;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedVm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 알람 정책 옵션 테스트 — 역할 옵션(토큰 역할·표시명·영역 수), 배포영역 옵션(역할 지정/전체, MDC→Namespace 순, areaId 체계, 404).
 *
 * 대역 스코프 (토큰 역할 ADMIN·USER):
 *   ADMIN — mdc-02: node-02 / mdc-01: node-01 / ns-b "Namespace B", ns-a "Namespace A" / 1세부 VM web-01, TB vm-1
 *   USER  — 자원 없음, 표시명(description) 없음
 */
class AlarmPolicyOptionServiceTest {

    AuthService authService = mock(AuthService.class);
    RoleRepository roleRepository = mock(RoleRepository.class);
    ResourceScopeService scopeService = mock(ResourceScopeService.class);
    AlarmPolicyOptionService service = new AlarmPolicyOptionService(authService, roleRepository, scopeService);

    static final Scope ADMIN_SCOPE = new Scope(Set.of("ADMIN"),
            List.of(new ScopedHost("ADMIN", 12L, "node-02", null, "mdc-02", ResourceStatus.ERROR, null, List.of()),
                    new ScopedHost("ADMIN", 11L, "node-01", null, "mdc-01", ResourceStatus.RUNNING, null, List.of()),
                    new ScopedHost("ADMIN", 13L, "node-03", null, "mdc-01", ResourceStatus.RUNNING, null, List.of())),
            List.of(new ScopedVm("ADMIN", "SEBU1:uuid-1", "web-01", ResourceStatus.RUNNING, "mdc-01", null),
                    new ScopedVm("ADMIN", "TB:ns-a/infra-1/vm-1", "vm-1", ResourceStatus.RUNNING, null, "ns-a")),
            List.of(new ScopedNamespace("ADMIN", "ns-b", "Namespace B"),
                    new ScopedNamespace("ADMIN", "ns-a", "Namespace A")));

    @BeforeEach
    void setUp() {
        when(authService.currentUserRoleIds()).thenReturn(Set.of("ADMIN", "USER"));
        when(roleRepository.findByName(anyString())).thenReturn(Optional.empty());
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(new Role("ADMIN", "관리자")));
        when(roleRepository.findByName("USER")).thenReturn(Optional.of(new Role("USER", "  ")));
        when(scopeService.currentUserScope()).thenReturn(new Scope(Set.of("ADMIN", "USER"),
                ADMIN_SCOPE.hosts(), ADMIN_SCOPE.vms(), ADMIN_SCOPE.namespaces()));
        when(scopeService.scopeOf(anyCollection())).thenReturn(new Scope(Set.of(), List.of(), List.of(), List.of()));
        when(scopeService.scopeOf(List.of("ADMIN"))).thenReturn(ADMIN_SCOPE);
    }

    @Test
    void 역할_옵션은_토큰_역할을_이름순으로_내리고_표시명은_description_없으면_roleName_이며_영역_수를_함께_준다() {
        List<AlarmRoleCandidateResponse> roles = service.getRoleCandidates();

        assertThat(roles).extracting(AlarmRoleCandidateResponse::roleName).containsExactly("ADMIN", "USER");
        assertThat(roles.get(0).name()).isEqualTo("관리자");
        assertThat(roles.get(0).areaCount()).isEqualTo(4);            // mdc-01, mdc-02, ns-a, ns-b
        assertThat(roles.get(1).name()).isEqualTo("USER");            // 공백 description 은 없는 것으로
        assertThat(roles.get(1).areaCount()).isZero();
    }

    @Test
    void 배포영역_옵션은_역할을_주면_그_역할의_MDC_다음_Namespace를_areaId_체계로_내린다() {
        List<AlarmAreaCandidateResponse> areas = service.getAreaCandidates("ADMIN");

        assertThat(areas).extracting(AlarmAreaCandidateResponse::areaId)
                .containsExactly("mdc-01", "mdc-02", "TB:ns-a", "TB:ns-b");
        assertThat(areas).extracting(AlarmAreaCandidateResponse::areaKind)
                .containsExactly(AreaKind.MDC, AreaKind.MDC, AreaKind.NAMESPACE, AreaKind.NAMESPACE);
        assertThat(areas).extracting(AlarmAreaCandidateResponse::name)
                .containsExactly("mdc-01", "mdc-02", "Namespace A", "Namespace B");
        assertThat(areas).extracting(AlarmAreaCandidateResponse::roleName).containsOnly("ADMIN");

        assertThat(service.getAreaCandidates("USER")).isEmpty();
        verify(scopeService, never()).currentUserScope();
    }

    @Test
    void 배포영역_옵션은_역할_없이_부르면_토큰_역할_전체를_역할순으로_묶어_내린다() {
        List<AlarmAreaCandidateResponse> areas = service.getAreaCandidates("  ");

        assertThat(areas).hasSize(4);
        assertThat(areas).extracting(AlarmAreaCandidateResponse::roleName).containsOnly("ADMIN");   // USER 는 영역 없음
        assertThat(areas.get(0).areaId()).isEqualTo("mdc-01");
        verify(scopeService, never()).scopeOf(anyCollection());
    }

    @Test
    void 토큰에_없는_역할의_배포영역은_404이고_스코프를_조회하지_않는다() {
        assertThatThrownBy(() -> service.getAreaCandidates("GUEST"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(scopeService, never()).scopeOf(anyCollection());
        verify(scopeService, never()).currentUserScope();
    }
}
