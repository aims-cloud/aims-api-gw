package com.innogrid.aims.monitoring.service.alarm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.repository.RoleRepository;
import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmAreaCandidateResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmRoleCandidateResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleAreaListItemResponse.AreaKind;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedNamespace;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관측 > 알람 — 정책 생성 위저드와 이력·정책 필터의 역할·배포영역 드롭다운 옵션 (docs/10 §5).
 *
 * 값 체계는 정책 원장·이력과 같다: 역할은 토큰 역할 이름({@code roleName}), 배포영역은 {@code areaId}(MDC 는 mdcId,
 * Tumblebug Namespace 는 {@code TB:{nsId}}). 원천은 자원 탐색기와 같은 스코프 원장({@link ResourceScopeService})이라
 * 탐색기 트리에 보이는 MDC·Namespace 와 항상 일치한다. 자원 탐색기 패널 API(root/roles·role/{roleName}/areas)는
 * 상태 집계·페이징이 붙은 화면용이라 옵션 용도로는 이 API 를 쓴다.
 */
@Service
@Transactional(readOnly = true)
public class AlarmPolicyOptionService {

    private final AuthService authService;
    private final RoleRepository roleRepository;
    private final ResourceScopeService resourceScopeService;

    public AlarmPolicyOptionService(AuthService authService, RoleRepository roleRepository,
                                    ResourceScopeService resourceScopeService) {
        this.authService = authService;
        this.roleRepository = roleRepository;
        this.resourceScopeService = resourceScopeService;
    }

    /** 역할 옵션 — 토큰 역할 전체(이름순). 표시명은 roles.description, 없으면 roleName. areaCount 는 고를 수 있는 배포영역 수 */
    public List<AlarmRoleCandidateResponse> getRoleCandidates() {
        Scope scope = resourceScopeService.currentUserScope();
        List<AlarmRoleCandidateResponse> rows = new ArrayList<>();
        for (String roleName : new TreeSet<>(scope.roles())) {
            String displayName = roleRepository.findByName(roleName).map(Role::getDescription)
                    .filter(d -> d != null && !d.isBlank())
                    .orElse(roleName);
            rows.add(new AlarmRoleCandidateResponse(roleName, displayName, areasOf(scope, roleName).size()));
        }
        return rows;
    }

    /**
     * 배포영역 옵션 — roleName 을 주면 그 역할의 MDC·Namespace, 없으면 토큰 역할 전체(행마다 roleName 포함).
     * MDC 먼저, 그 다음 Namespace, 각각 이름순. 토큰에 없는 역할은 404(존재를 드러내지 않음).
     */
    public List<AlarmAreaCandidateResponse> getAreaCandidates(String roleName) {
        Set<String> userRoles = authService.currentUserRoleIds();
        String role = roleName == null || roleName.isBlank() ? null : roleName.trim();
        if (role != null && !userRoles.contains(role)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "역할을 찾을 수 없습니다: " + role);
        }
        Scope scope = role == null ? resourceScopeService.currentUserScope() : resourceScopeService.scopeOf(List.of(role));
        List<String> roles = role == null ? new ArrayList<>(new TreeSet<>(scope.roles())) : List.of(role);

        List<AlarmAreaCandidateResponse> rows = new ArrayList<>();
        for (String r : roles) {
            rows.addAll(areasOf(scope, r));
        }
        return rows;
    }

    // ── 내부 ────────────────────────────────────────────────────

    /** 역할 1개의 배포영역 — 물리 호스트가 속한 MDC(id 순) + 매핑된 Tumblebug Namespace(표시명 순) */
    private static List<AlarmAreaCandidateResponse> areasOf(Scope scope, String roleName) {
        List<AlarmAreaCandidateResponse> rows = new ArrayList<>();
        Set<String> mdcIds = new TreeSet<>();
        for (ScopedHost host : scope.hostsOf(roleName)) {
            mdcIds.add(host.mdcId());
        }
        for (String mdcId : mdcIds) {
            rows.add(new AlarmAreaCandidateResponse(roleName, mdcId, AreaKind.MDC, mdcId));
        }
        List<ScopedNamespace> namespaces = new ArrayList<>(scope.namespacesOf(roleName));
        namespaces.sort(Comparator.comparing(ScopedNamespace::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ScopedNamespace::nsId));
        for (ScopedNamespace ns : namespaces) {
            rows.add(new AlarmAreaCandidateResponse(roleName, "TB:" + ns.nsId(), AreaKind.NAMESPACE, ns.name()));
        }
        return rows;
    }
}
