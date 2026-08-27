package com.innogrid.aims.resource.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import com.innogrid.aims.cloudbarista.spider.SpiderClient;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.resource.entity.VmGroup;
import com.innogrid.aims.resource.entity.VmGroupActionHistory;
import com.innogrid.aims.resource.entity.VmGroupMember;
import com.innogrid.aims.resource.repository.VmGroupActionHistoryRepository;
import com.innogrid.aims.resource.repository.VmGroupRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VmGroupServiceTest {

    private VmGroupRepository groupRepository;
    private VmGroupActionHistoryRepository historyRepository;
    private SpiderClient spiderClient;
    private VmGroupService service;

    @BeforeEach
    void setUp() {
        groupRepository = mock(VmGroupRepository.class);
        historyRepository = mock(VmGroupActionHistoryRepository.class);
        spiderClient = mock(SpiderClient.class);
        service = new VmGroupService(groupRepository, historyRepository, spiderClient);
    }

    private static VmGroup groupWithMembers(VmGroupMember... members) {
        VmGroup group = new VmGroup("web-group", "웹 서버 그룹", "aws-seoul");
        group.replaceMembers(List.of(members));
        return group;
    }

    @Test
    void create_group_saves_and_records_history() {
        when(groupRepository.existsByName("web-group")).thenReturn(false);
        when(groupRepository.save(any(VmGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        VmGroup created = service.createGroup("web-group", "설명", "aws-seoul",
                List.of(new VmGroupMember("aws-seoul", "vm-1")), "admin@aims.cloud");

        assertThat(created.getName()).isEqualTo("web-group");
        assertThat(created.getMembers()).hasSize(1);
        ArgumentCaptor<VmGroupActionHistory> captor =
                ArgumentCaptor.forClass(VmGroupActionHistory.class);
        verify(historyRepository).save(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo(VmGroupActionHistory.Action.CREATE);
        assertThat(captor.getValue().getPerformedBy()).isEqualTo("admin@aims.cloud");
    }

    @Test
    void create_group_with_duplicate_name_throws_conflict() {
        when(groupRepository.existsByName("web-group")).thenReturn(true);

        assertThatThrownBy(() -> service.createGroup("web-group", null, null, List.of(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void get_group_throws_not_found() {
        when(groupRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getGroup(99L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void start_group_resumes_all_members_and_records_success() {
        VmGroup group = groupWithMembers(
                new VmGroupMember("aws-seoul", "vm-1"),
                new VmGroupMember("aws-seoul", "vm-2"));
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));

        Map<String, Object> result = service.startGroup(1L, "admin@aims.cloud");

        verify(spiderClient).controlVM("aws-seoul", "vm-1", "resume");
        verify(spiderClient).controlVM("aws-seoul", "vm-2", "resume");
        assertThat(result)
                .containsEntry("total", 2)
                .containsEntry("success", 2)
                .containsEntry("result", "SUCCESS");
    }

    @Test
    void stop_group_suspends_members() {
        VmGroup group = groupWithMembers(new VmGroupMember("aws-seoul", "vm-1"));
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));

        Map<String, Object> result = service.stopGroup(1L, null);

        verify(spiderClient).controlVM("aws-seoul", "vm-1", "suspend");
        assertThat(result).containsEntry("action", "STOP");
    }

    @Test
    void partial_failure_records_partial_result() {
        VmGroup group = groupWithMembers(
                new VmGroupMember("aws-seoul", "vm-ok"),
                new VmGroupMember("aws-seoul", "vm-bad"));
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
        doThrow(new RuntimeException("spider error"))
                .when(spiderClient).controlVM("aws-seoul", "vm-bad", "resume");

        Map<String, Object> result = service.startGroup(1L, null);

        assertThat(result)
                .containsEntry("success", 1)
                .containsEntry("result", "PARTIAL");
        assertThat(result.get("failed")).isEqualTo(List.of("vm-bad"));

        ArgumentCaptor<VmGroupActionHistory> captor =
                ArgumentCaptor.forClass(VmGroupActionHistory.class);
        verify(historyRepository).save(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo(VmGroupActionHistory.Result.PARTIAL);
    }

    @Test
    void start_empty_group_throws_bad_request() {
        VmGroup group = groupWithMembers();
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));

        assertThatThrownBy(() -> service.startGroup(1L, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void group_detail_includes_member_vm_status() {
        VmGroup group = groupWithMembers(new VmGroupMember("aws-seoul", "vm-1"));
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
        when(spiderClient.getVMStatus("aws-seoul", "vm-1"))
                .thenReturn(Map.of("Status", "Running"));

        Map<String, Object> detail = service.getGroupDetail(1L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> vms = (List<Map<String, Object>>) detail.get("vms");
        assertThat(vms).hasSize(1);
        assertThat(vms.get(0))
                .containsEntry("vmName", "vm-1")
                .containsEntry("status", "Running");
    }

    @Test
    void group_detail_marks_unknown_status_on_spider_error() {
        VmGroup group = groupWithMembers(new VmGroupMember("aws-seoul", "vm-1"));
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));
        when(spiderClient.getVMStatus(anyString(), anyString()))
                .thenThrow(new RuntimeException("timeout"));

        Map<String, Object> detail = service.getGroupDetail(1L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> vms = (List<Map<String, Object>>) detail.get("vms");
        assertThat(vms.get(0)).containsEntry("status", "UNKNOWN");
    }

    @Test
    void delete_group_records_history() {
        VmGroup group = groupWithMembers();
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));

        service.deleteGroup(1L, "admin@aims.cloud");

        verify(groupRepository).delete(group);
        ArgumentCaptor<VmGroupActionHistory> captor =
                ArgumentCaptor.forClass(VmGroupActionHistory.class);
        verify(historyRepository).save(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo(VmGroupActionHistory.Action.DELETE);
    }

    @Test
    void histories_query_validates_group_exists() {
        when(groupRepository.findById(1L)).thenReturn(Optional.of(groupWithMembers()));
        when(historyRepository.findByVmGroupIdOrderByCreatedAtDesc(eq(1L)))
                .thenReturn(List.of());

        assertThat(service.getGroupHistories(1L)).isEmpty();
        verify(historyRepository).findByVmGroupIdOrderByCreatedAtDesc(1L);
    }
}
