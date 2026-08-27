package com.innogrid.aims.resource.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.cloudbarista.spider.SpiderClient;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.resource.entity.VmGroup;
import com.innogrid.aims.resource.entity.VmGroupActionHistory;
import com.innogrid.aims.resource.entity.VmGroupActionHistory.Action;
import com.innogrid.aims.resource.entity.VmGroupActionHistory.Result;
import com.innogrid.aims.resource.entity.VmGroupMember;
import com.innogrid.aims.resource.repository.VmGroupActionHistoryRepository;
import com.innogrid.aims.resource.repository.VmGroupRepository;

/**
 * 가상서버 그룹 서비스.
 * 그룹 CRUD와 일괄 동작(시작/중지)을 제공하고, 모든 행위를 이력으로 남긴다.
 * VM 제어는 CB-Spider에 위임한다 (start=resume, stop=suspend).
 */
@Service
public class VmGroupService {

    private static final Logger log = LoggerFactory.getLogger(VmGroupService.class);

    private final VmGroupRepository groupRepository;
    private final VmGroupActionHistoryRepository historyRepository;
    private final SpiderClient spiderClient;

    public VmGroupService(VmGroupRepository groupRepository,
                          VmGroupActionHistoryRepository historyRepository,
                          SpiderClient spiderClient) {
        this.groupRepository = groupRepository;
        this.historyRepository = historyRepository;
        this.spiderClient = spiderClient;
    }

    // ── CRUD ────────────────────────────────────────────────────

    public List<VmGroup> listGroups() {
        return groupRepository.findAll();
    }

    public VmGroup getGroup(Long groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "가상서버 그룹을 찾을 수 없습니다: " + groupId));
    }

    @Transactional
    public VmGroup createGroup(String name, String description, String deployZone,
                               List<VmGroupMember> members, String performedBy) {
        if (groupRepository.existsByName(name)) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "이미 존재하는 그룹 이름입니다: " + name);
        }
        VmGroup group = new VmGroup(name, description, deployZone);
        group.replaceMembers(members);
        VmGroup saved = groupRepository.save(group);
        historyRepository.save(new VmGroupActionHistory(saved.getId(), Action.CREATE,
                Result.SUCCESS, "그룹 생성 (멤버 " + saved.getMembers().size() + "대)", performedBy));
        return saved;
    }

    @Transactional
    public VmGroup updateGroup(Long groupId, String name, String description, String deployZone,
                               List<VmGroupMember> members, String performedBy) {
        VmGroup group = getGroup(groupId);
        if (name != null && !name.isBlank() && !name.equals(group.getName())) {
            if (groupRepository.existsByName(name)) {
                throw new BusinessException(HttpStatus.CONFLICT,
                        "이미 존재하는 그룹 이름입니다: " + name);
            }
            group.setName(name);
        }
        if (description != null) group.setDescription(description);
        if (deployZone != null) group.setDeployZone(deployZone);
        if (members != null) group.replaceMembers(members);

        VmGroup saved = groupRepository.save(group);
        historyRepository.save(new VmGroupActionHistory(groupId, Action.UPDATE,
                Result.SUCCESS, "그룹 수정", performedBy));
        return saved;
    }

    @Transactional
    public void deleteGroup(Long groupId, String performedBy) {
        VmGroup group = getGroup(groupId);
        groupRepository.delete(group);
        historyRepository.save(new VmGroupActionHistory(groupId, Action.DELETE,
                Result.SUCCESS, "그룹 삭제: " + group.getName(), performedBy));
    }

    // ── 상세 (소속 가상서버 상태 포함) ──────────────────────────

    /** 그룹 상세 — 멤버 VM별 현재 상태를 CB-Spider에서 조회하여 함께 반환. */
    public Map<String, Object> getGroupDetail(Long groupId) {
        VmGroup group = getGroup(groupId);

        List<Map<String, Object>> vms = new ArrayList<>(group.getMembers().size());
        for (VmGroupMember member : group.getMembers()) {
            Map<String, Object> vm = new LinkedHashMap<>();
            vm.put("connectionName", member.getConnectionName());
            vm.put("vmName", member.getVmName());
            try {
                Map<String, Object> status =
                        spiderClient.getVMStatus(member.getConnectionName(), member.getVmName());
                vm.put("status", status == null ? null : status.get("Status"));
            } catch (Exception e) {
                log.warn("VM 상태 조회 실패 {}/{}: {}",
                        member.getConnectionName(), member.getVmName(), e.getMessage());
                vm.put("status", "UNKNOWN");
            }
            vms.add(vm);
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", group.getId());
        detail.put("name", group.getName());
        detail.put("description", group.getDescription());
        detail.put("deployZone", group.getDeployZone());
        detail.put("createdAt", group.getCreatedAt());
        detail.put("updatedAt", group.getUpdatedAt());
        detail.put("vms", vms);
        return detail;
    }

    public List<VmGroupActionHistory> getGroupHistories(Long groupId) {
        getGroup(groupId); // 존재 검증
        return historyRepository.findByVmGroupIdOrderByCreatedAtDesc(groupId);
    }

    // ── 일괄 동작 ───────────────────────────────────────────────

    /** 그룹 시작 — 멤버 VM 전체 resume. */
    @Transactional
    public Map<String, Object> startGroup(Long groupId, String performedBy) {
        return controlGroup(groupId, Action.START, "resume", performedBy);
    }

    /** 그룹 중지 — 멤버 VM 전체 suspend. */
    @Transactional
    public Map<String, Object> stopGroup(Long groupId, String performedBy) {
        return controlGroup(groupId, Action.STOP, "suspend", performedBy);
    }

    private Map<String, Object> controlGroup(Long groupId, Action action,
                                             String spiderAction, String performedBy) {
        VmGroup group = getGroup(groupId);
        if (group.getMembers().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "그룹에 소속된 VM이 없습니다");
        }

        int success = 0;
        List<String> failures = new ArrayList<>();
        for (VmGroupMember member : group.getMembers()) {
            try {
                spiderClient.controlVM(member.getConnectionName(), member.getVmName(), spiderAction);
                success++;
            } catch (Exception e) {
                log.warn("VM {} 실패 {}/{}: {}", spiderAction,
                        member.getConnectionName(), member.getVmName(), e.getMessage());
                failures.add(member.getVmName());
            }
        }

        int total = group.getMembers().size();
        Result result = failures.isEmpty() ? Result.SUCCESS
                : success == 0 ? Result.FAILURE : Result.PARTIAL;
        String detail = success + "/" + total + " VM " + spiderAction + " 성공"
                + (failures.isEmpty() ? "" : ", 실패: " + String.join(", ", failures));
        historyRepository.save(new VmGroupActionHistory(groupId, action, result, detail, performedBy));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("groupId", groupId);
        response.put("action", action.name());
        response.put("total", total);
        response.put("success", success);
        response.put("failed", failures);
        response.put("result", result.name());
        return response;
    }
}
