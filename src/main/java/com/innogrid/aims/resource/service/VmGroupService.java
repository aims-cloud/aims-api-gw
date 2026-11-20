package com.innogrid.aims.resource.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.openstack.OpenStackServerCatalog;
import com.innogrid.aims.openstack.OpenStackServerCatalog.ServerInfo;
import com.innogrid.aims.resource.dto.VmGroupDtos.CandidateServer;
import com.innogrid.aims.resource.dto.VmGroupDtos.EdgeRequest;
import com.innogrid.aims.resource.dto.VmGroupDtos.GroupServer;
import com.innogrid.aims.resource.dto.VmGroupDtos.GroupSummary;
import com.innogrid.aims.resource.dto.VmGroupDtos.HistoryRow;
import com.innogrid.aims.resource.dto.VmGroupDtos.NetworkEdge;
import com.innogrid.aims.resource.dto.VmGroupDtos.NetworkMap;
import com.innogrid.aims.resource.dto.VmGroupDtos.NetworkNode;
import com.innogrid.aims.resource.entity.VmGroup;
import com.innogrid.aims.resource.entity.VmGroupActionHistory;
import com.innogrid.aims.resource.entity.VmGroupActionHistory.Action;
import com.innogrid.aims.resource.entity.VmGroupActionHistory.Result;
import com.innogrid.aims.resource.entity.VmGroupActionHistory.Target;
import com.innogrid.aims.resource.entity.VmGroupConnection;
import com.innogrid.aims.resource.entity.VmGroupMember;
import com.innogrid.aims.resource.repository.VmGroupActionHistoryRepository;
import com.innogrid.aims.resource.repository.VmGroupConnectionRepository;
import com.innogrid.aims.resource.repository.VmGroupMemberRepository;
import com.innogrid.aims.resource.repository.VmGroupRepository;

/**
 * 가상서버 그룹 서비스 (통합 토폴로지 OpenStack 기준).
 * 그룹 CRUD, 가상서버 담기/빼기, 네트워크 맵(연결) 관리, 행위 이력을 제공한다.
 * VM 후보·상태는 {@link OpenStackServerCatalog}(Nova)에서 가져온다.
 */
@Service
public class VmGroupService {

    private final VmGroupRepository groupRepository;
    private final VmGroupMemberRepository memberRepository;
    private final VmGroupConnectionRepository connectionRepository;
    private final VmGroupActionHistoryRepository historyRepository;
    private final OpenStackServerCatalog serverCatalog;

    public VmGroupService(VmGroupRepository groupRepository,
                          VmGroupMemberRepository memberRepository,
                          VmGroupConnectionRepository connectionRepository,
                          VmGroupActionHistoryRepository historyRepository,
                          OpenStackServerCatalog serverCatalog) {
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
        this.connectionRepository = connectionRepository;
        this.historyRepository = historyRepository;
        this.serverCatalog = serverCatalog;
    }

    // ══ 그룹 CRUD ═══════════════════════════════════════════════

    public List<GroupSummary> listGroups() {
        List<GroupSummary> result = new ArrayList<>();
        for (VmGroup group : groupRepository.findAll()) {
            result.add(toSummary(group));
        }
        return result;
    }

    public GroupSummary getGroupSummary(Long groupId) {
        return toSummary(getGroup(groupId));
    }

    @Transactional
    public GroupSummary createGroup(String deployZone, String name, String description,
                                    boolean enabled, String performedBy, String clientIp) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        if (deployZone == null || deployZone.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "배포영역을 선택해주세요");
        }
        if (name == null || name.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "가상서버 그룹명을 입력해주세요");
        }
        VmGroup group = groupRepository.save(new VmGroup(deployZone, name, description, enabled, performedBy));
        record(group.getId(), Target.GROUP, Action.CREATE, Result.SUCCESS,
                "가상서버 그룹('" + name + "')이/가 생성 되었습니다.", performedBy, clientIp, startedAt);
        return toSummary(group);
    }

    @Transactional
    public GroupSummary updateGroup(Long groupId, String deployZone, String name, String description,
                                    Boolean enabled, String performedBy, String clientIp) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        VmGroup group = getGroup(groupId);
        if (deployZone != null && !deployZone.isBlank()) group.setDeployZone(deployZone);
        if (name != null && !name.isBlank()) group.setName(name);
        if (description != null) group.setDescription(description);
        if (enabled != null) group.setEnabled(enabled);
        groupRepository.save(group);
        record(groupId, Target.GROUP, Action.UPDATE, Result.SUCCESS,
                "가상서버 그룹('" + group.getName() + "')이/가 수정 되었습니다.", performedBy, clientIp, startedAt);
        return toSummary(group);
    }

    @Transactional
    public void deleteGroup(Long groupId, String performedBy, String clientIp) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        VmGroup group = getGroup(groupId);
        memberRepository.deleteByVmGroupId(groupId);
        connectionRepository.deleteByVmGroupId(groupId);
        groupRepository.delete(group);
        record(groupId, Target.GROUP, Action.DELETE, Result.SUCCESS,
                "가상서버 그룹('" + group.getName() + "')이/가 삭제 되었습니다.", performedBy, clientIp, startedAt);
    }

    // ══ 가상서버 후보 / 담기·빼기 ═══════════════════════════════

    /** 가상서버 추가 모달 후보 — 배포영역의 OpenStack 서버 중 아직 담기지 않은 것. */
    public List<CandidateServer> listCandidates(Long groupId) {
        getGroup(groupId);
        List<String> alreadyIn = memberRepository.findByVmGroupIdOrderByCreatedAtAsc(groupId)
                .stream().map(VmGroupMember::getServerId).toList();

        List<CandidateServer> candidates = new ArrayList<>();
        for (ServerInfo server : serverCatalog.listServers()) {
            if (alreadyIn.contains(server.serverId())) continue;
            candidates.add(new CandidateServer(server.serverId(), server.deployZone(),
                    server.vmName(), server.ip(), server.status()));
        }
        return candidates;
    }

    /** 그룹의 가상서버 목록 (실시간 상태 + 연결 엔드포인트 포함). */
    public List<GroupServer> listServers(Long groupId) {
        getGroup(groupId);
        Map<String, String> liveStatus = serverCatalog.statusByServerId();
        Map<String, List<String>> endpointsByServer = endpointsByServer(groupId);

        List<GroupServer> result = new ArrayList<>();
        for (VmGroupMember m : memberRepository.findByVmGroupIdOrderByCreatedAtAsc(groupId)) {
            result.add(new GroupServer(m.getId(), m.getServerId(), null, m.getVmName(),
                    m.getHost(), m.getIp(),
                    endpointsByServer.getOrDefault(m.getServerId(), List.of()),
                    liveStatus.getOrDefault(m.getServerId(), "Deleted")));
        }
        return result;
    }

    @Transactional
    public void addServers(Long groupId, List<String> serverIds, String performedBy, String clientIp) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        VmGroup group = getGroup(groupId);
        if (serverIds == null || serverIds.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "추가할 가상서버를 선택해주세요");
        }
        int added = 0;
        for (String serverId : serverIds) {
            if (memberRepository.existsByVmGroupIdAndServerId(groupId, serverId)) continue;
            ServerInfo info = serverCatalog.getServer(serverId);
            String vmName = info != null ? info.vmName() : serverId;
            String host = info != null ? info.host() : null;
            String ip = info != null ? info.ip() : null;
            memberRepository.save(new VmGroupMember(groupId, serverId, vmName, host, ip));
            added++;
        }
        record(groupId, Target.SERVER, Action.SERVER_ADD, Result.SUCCESS,
                "가상서버 그룹('" + group.getName() + "')이/가 가상서버 추가 되었습니다. (" + added + "대)",
                performedBy, clientIp, startedAt);
    }

    @Transactional
    public void removeServer(Long groupId, Long memberId, String performedBy, String clientIp) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        VmGroup group = getGroup(groupId);
        VmGroupMember member = memberRepository.findById(memberId)
                .filter(m -> m.getVmGroupId().equals(groupId))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "가상서버를 찾을 수 없습니다"));
        // 이 서버가 낀 연결도 함께 정리
        List<VmGroupConnection> stale = connectionRepository.findByVmGroupId(groupId).stream()
                .filter(c -> member.getServerId().equals(c.getSourceServerId())
                        || member.getServerId().equals(c.getTargetServerId()))
                .toList();
        connectionRepository.deleteAll(stale);
        memberRepository.delete(member);
        record(groupId, Target.SERVER, Action.SERVER_REMOVE, Result.SUCCESS,
                "가상서버 그룹('" + group.getName() + "')이/가 가상서버 제거 되었습니다.",
                performedBy, clientIp, startedAt);
    }

    // ══ 네트워크 맵 ═════════════════════════════════════════════

    public NetworkMap getNetworkMap(Long groupId) {
        getGroup(groupId);
        Map<String, String> liveStatus = serverCatalog.statusByServerId();

        List<NetworkNode> nodes = new ArrayList<>();
        for (VmGroupMember m : memberRepository.findByVmGroupIdOrderByCreatedAtAsc(groupId)) {
            nodes.add(new NetworkNode(m.getServerId(), m.getVmName(), m.getIp(),
                    liveStatus.getOrDefault(m.getServerId(), "Deleted")));
        }
        List<NetworkEdge> edges = new ArrayList<>();
        for (VmGroupConnection c : connectionRepository.findByVmGroupId(groupId)) {
            edges.add(new NetworkEdge(c.getId(), c.getSourceServerId(), c.getSourceEndpoint(),
                    c.getTargetServerId(), c.getTargetEndpoint()));
        }
        return new NetworkMap(groupId, nodes, edges);
    }

    /** 네트워크 맵 저장 — 연결 전체 교체 (서버 연결 관리 액션). */
    @Transactional
    public NetworkMap saveNetworkMap(Long groupId, List<EdgeRequest> edges,
                                     String performedBy, String clientIp) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        VmGroup group = getGroup(groupId);
        connectionRepository.deleteByVmGroupId(groupId);
        if (edges != null) {
            for (EdgeRequest e : edges) {
                connectionRepository.save(new VmGroupConnection(groupId,
                        e.sourceServerId(), e.sourceEndpoint(), e.targetServerId(), e.targetEndpoint()));
            }
        }
        record(groupId, Target.CONNECTION, Action.CONNECTION_UPDATE, Result.SUCCESS,
                "가상서버 그룹('" + group.getName() + "')이/가 연결 정보 수정 되었습니다.",
                performedBy, clientIp, startedAt);
        return getNetworkMap(groupId);
    }

    // ══ 행위 이력 ═══════════════════════════════════════════════

    public List<HistoryRow> getHistories(Long groupId) {
        getGroup(groupId);
        List<HistoryRow> rows = new ArrayList<>();
        for (VmGroupActionHistory h : historyRepository.findByVmGroupIdOrderByStartedAtDesc(groupId)) {
            rows.add(new HistoryRow(h.getId(), h.getPerformedBy(), h.getClientIp(),
                    h.getTarget().name(), h.getAction().name(), h.getResult().name(),
                    h.getDetail(), h.getStartedAt(), h.getCompletedAt()));
        }
        return rows;
    }

    // ══ 공통 ════════════════════════════════════════════════════

    private VmGroup getGroup(Long groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "가상서버 그룹을 찾을 수 없습니다: " + groupId));
    }

    private GroupSummary toSummary(VmGroup group) {
        return new GroupSummary(group.getId(), group.getDeployZone(), group.getName(),
                group.getDescription(), group.isEnabled(), group.getCreatedBy(), group.getCreatedAt(),
                (int) memberRepository.countByVmGroupId(group.getId()),
                connectionRepository.findByVmGroupId(group.getId()).size());
    }

    /** 서버별 연결 엔드포인트 목록 (가상서버 탭의 '연결(IP:PORT)' 컬럼). */
    private Map<String, List<String>> endpointsByServer(Long groupId) {
        Map<String, List<String>> map = new HashMap<>();
        for (VmGroupConnection c : connectionRepository.findByVmGroupId(groupId)) {
            if (c.getSourceEndpoint() != null) {
                map.computeIfAbsent(c.getSourceServerId(), k -> new ArrayList<>()).add(c.getSourceEndpoint());
            }
            if (c.getTargetEndpoint() != null) {
                map.computeIfAbsent(c.getTargetServerId(), k -> new ArrayList<>()).add(c.getTargetEndpoint());
            }
        }
        return map;
    }

    private void record(Long groupId, Target target, Action action, Result result,
                        String detail, String performedBy, String clientIp, OffsetDateTime startedAt) {
        historyRepository.save(new VmGroupActionHistory(groupId, target, action, result,
                detail, performedBy, clientIp, startedAt));
    }
}
