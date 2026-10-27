package com.innogrid.aims.infra.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.auth.dto.AuthDto.NamespaceCandidateResponse;
import com.innogrid.aims.auth.dto.AuthDto.RoleNamespaceResponse;
import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.repository.RoleRepository;
import com.innogrid.aims.cloudbarista.tumblebug.TumblebugClient;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.RoleNamespace;
import com.innogrid.aims.infra.repository.RoleNamespaceRepository;

/**
 * 역할 ↔ CB-Tumblebug Namespace 배정 관리 — 자원 탐색기 가상머신 가지·알람 정책의 역할별 접근 범위 원장
 * ({@code role_namespaces}, docs/06 §9.1)을 API 로 다룬다.
 *
 * <p>배정할 nsId 는 Tumblebug 에 실제로 있어야 한다(오타 배정 방지). Tumblebug 에 연결하지 못하면 그 예외가 그대로
 * 올라가 공통 처리기가 503 으로 내린다. 배정 목록 조회는 DB 만으로 답하고, 표시 이름만 Tumblebug 에서 덧붙인다.</p>
 */
@Service
public class RoleNamespaceService {

    private static final Logger log = LoggerFactory.getLogger(RoleNamespaceService.class);

    /** Tumblebug Namespace 1개 — 표시 이름이 없으면 id */
    private record TbNamespace(String id, String name, String description) {}

    private final RoleRepository roleRepository;
    private final RoleNamespaceRepository roleNamespaceRepository;
    private final TumblebugClient tumblebug;

    public RoleNamespaceService(RoleRepository roleRepository, RoleNamespaceRepository roleNamespaceRepository,
                                TumblebugClient tumblebug) {
        this.roleRepository = roleRepository;
        this.roleNamespaceRepository = roleNamespaceRepository;
        this.tumblebug = tumblebug;
    }

    /** 역할에 배정된 Namespace(nsId 순). 표시 이름은 Tumblebug 에서 — 조회 실패·부재면 null */
    @Transactional(readOnly = true)
    public List<RoleNamespaceResponse> assigned(Long roleId) {
        requireRole(roleId);
        List<RoleNamespace> mappings = roleNamespaceRepository.findByRole_Id(roleId);
        if (mappings.isEmpty()) {
            return List.of();
        }
        Map<String, TbNamespace> namespaces;
        try {
            namespaces = tumblebugNamespaces();
        } catch (RuntimeException e) {
            log.warn("Tumblebug Namespace 조회 실패 — 배정 목록의 표시 이름을 비웁니다: {}", e.getMessage());
            namespaces = Map.of();
        }
        return rows(mappings, namespaces);
    }

    /** 배정 후보 — Tumblebug 의 전체 Namespace(nsId 순)와 이 역할의 배정 여부 */
    @Transactional(readOnly = true)
    public List<NamespaceCandidateResponse> candidates(Long roleId) {
        requireRole(roleId);
        Set<String> assigned = assignedIds(roleId);
        return tumblebugNamespaces().values().stream()
                .sorted(Comparator.comparing(TbNamespace::id))
                .map(ns -> new NamespaceCandidateResponse(ns.id(), ns.name(), ns.description(), assigned.contains(ns.id())))
                .toList();
    }

    /** 배정 추가 — Tumblebug 에 없는 nsId 가 하나라도 있으면 아무것도 넣지 않고 400. 이미 배정된 nsId 는 건너뛴다 */
    @Transactional
    public List<RoleNamespaceResponse> assign(Long roleId, List<String> nsIds) {
        Role role = requireRole(roleId);
        Set<String> requested = new LinkedHashSet<>();
        if (nsIds != null) {
            for (String nsId : nsIds) {
                if (nsId != null && !nsId.isBlank()) {
                    requested.add(nsId.trim());
                }
            }
        }
        if (requested.isEmpty()) {
            throw BusinessException.badRequest("배정할 Namespace 가 비어 있습니다 (nsIds)");
        }
        Map<String, TbNamespace> namespaces = tumblebugNamespaces();
        List<String> unknown = requested.stream().filter(id -> !namespaces.containsKey(id)).toList();
        if (!unknown.isEmpty()) {
            throw BusinessException.badRequest("Tumblebug 에 없는 Namespace 입니다: " + String.join(", ", unknown));
        }
        Set<String> assigned = assignedIds(roleId);
        for (String nsId : requested) {
            if (!assigned.contains(nsId)) {
                roleNamespaceRepository.save(new RoleNamespace(role, nsId));
            }
        }
        return rows(roleNamespaceRepository.findByRole_Id(roleId), namespaces);
    }

    /** 배정 해제 — 배정되어 있지 않으면 404 */
    @Transactional
    public void unassign(Long roleId, String nsId) {
        requireRole(roleId);
        RoleNamespace mapping = roleNamespaceRepository.findByRole_IdAndNsId(roleId, nsId)
                .orElseThrow(() -> BusinessException.notFound("역할에 배정되지 않은 Namespace 입니다: " + nsId));
        roleNamespaceRepository.delete(mapping);
    }

    /** 역할 삭제 — Namespace 배정을 먼저 지운다(role_namespaces.role_id 외래키에 ON DELETE CASCADE 가 없다) */
    @Transactional
    public void deleteRole(Long roleId) {
        roleNamespaceRepository.deleteAll(roleNamespaceRepository.findByRole_Id(roleId));
        roleRepository.deleteById(roleId);
    }

    // ── 내부 ────────────────────────────────────────────────────

    private Role requireRole(Long roleId) {
        return roleRepository.findById(roleId)
                .orElseThrow(() -> BusinessException.notFound("역할을 찾을 수 없습니다"));
    }

    private static List<RoleNamespaceResponse> rows(List<RoleNamespace> mappings, Map<String, TbNamespace> namespaces) {
        List<RoleNamespaceResponse> rows = new ArrayList<>();
        for (RoleNamespace mapping : mappings) {
            TbNamespace ns = namespaces.get(mapping.getNsId());
            rows.add(new RoleNamespaceResponse(mapping.getNsId(), ns == null ? null : ns.name(), mapping.getCreatedAt()));
        }
        rows.sort(Comparator.comparing(RoleNamespaceResponse::nsId));
        return rows;
    }

    private Set<String> assignedIds(Long roleId) {
        Set<String> ids = new LinkedHashSet<>();
        for (RoleNamespace mapping : roleNamespaceRepository.findByRole_Id(roleId)) {
            ids.add(mapping.getNsId());
        }
        return ids;
    }

    /** Tumblebug {@code GET /ns} → id 별 Namespace. 연결 실패 예외는 호출 측에 그대로 올린다 */
    private Map<String, TbNamespace> tumblebugNamespaces() {
        Map<String, TbNamespace> byId = new LinkedHashMap<>();
        Map<String, Object> response = tumblebug.listNamespaces();
        if (response != null && response.get("ns") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> ns && ns.get("id") != null && !ns.get("id").toString().isBlank()) {
                    String id = ns.get("id").toString();
                    String name = ns.get("name") == null || ns.get("name").toString().isBlank() ? id : ns.get("name").toString();
                    String description = ns.get("description") == null ? null : ns.get("description").toString();
                    byId.put(id, new TbNamespace(id, name, description));
                }
            }
        }
        return byId;
    }
}
