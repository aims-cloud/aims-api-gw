package com.innogrid.aims.resource.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.openstack.OpenStackServerCatalog;
import com.innogrid.aims.openstack.OpenStackServerCatalog.ServerInfo;
import com.innogrid.aims.resource.dto.VmGroupDtos.CandidateServer;
import com.innogrid.aims.resource.dto.VmGroupDtos.EdgeRequest;
import com.innogrid.aims.resource.dto.VmGroupDtos.GroupServer;
import com.innogrid.aims.resource.dto.VmGroupDtos.GroupSummary;
import com.innogrid.aims.resource.entity.VmGroup;
import com.innogrid.aims.resource.entity.VmGroupActionHistory;
import com.innogrid.aims.resource.entity.VmGroupConnection;
import com.innogrid.aims.resource.entity.VmGroupMember;
import com.innogrid.aims.resource.repository.VmGroupActionHistoryRepository;
import com.innogrid.aims.resource.repository.VmGroupConnectionRepository;
import com.innogrid.aims.resource.repository.VmGroupMemberRepository;
import com.innogrid.aims.resource.repository.VmGroupRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VmGroupServiceTest {

    private VmGroupRepository groupRepository;
    private VmGroupMemberRepository memberRepository;
    private VmGroupConnectionRepository connectionRepository;
    private VmGroupActionHistoryRepository historyRepository;
    private OpenStackServerCatalog catalog;
    private VmGroupService service;

    @BeforeEach
    void setUp() {
        groupRepository = mock(VmGroupRepository.class);
        memberRepository = mock(VmGroupMemberRepository.class);
        connectionRepository = mock(VmGroupConnectionRepository.class);
        historyRepository = mock(VmGroupActionHistoryRepository.class);
        catalog = mock(OpenStackServerCatalog.class);
        service = new VmGroupService(groupRepository, memberRepository, connectionRepository,
                historyRepository, catalog);

        when(connectionRepository.findByVmGroupId(any())).thenReturn(List.of());
        when(memberRepository.countByVmGroupId(any())).thenReturn(0L);
    }

    private VmGroup group() {
        return new VmGroup("rnd-openstack", "web-group", "설명", true, "admin");
    }

    @Test
    void create_group_records_history_with_client_ip() {
        when(groupRepository.save(any(VmGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        GroupSummary summary = service.createGroup("rnd-openstack", "web-group", "설명", true,
                "admin", "192.168.0.1");

        assertThat(summary.name()).isEqualTo("web-group");
        assertThat(summary.enabled()).isTrue();
        ArgumentCaptor<VmGroupActionHistory> cap = ArgumentCaptor.forClass(VmGroupActionHistory.class);
        verify(historyRepository).save(cap.capture());
        assertThat(cap.getValue().getAction()).isEqualTo(VmGroupActionHistory.Action.CREATE);
        assertThat(cap.getValue().getClientIp()).isEqualTo("192.168.0.1");
    }

    @Test
    void create_group_requires_deploy_zone_and_name() {
        assertThatThrownBy(() -> service.createGroup(null, "g", null, true, "admin", "ip"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.createGroup("zone", " ", null, true, "admin", "ip"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void candidates_exclude_already_added_servers() {
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group()));
        when(memberRepository.findByVmGroupIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(
                new VmGroupMember(1L, "srv-1", "vm1", "com-1", "10.0.0.5")));
        when(catalog.listServers()).thenReturn(List.of(
                new ServerInfo("srv-1", "rnd-openstack", "vm1", "com-1", "10.0.0.5", "ACTIVE"),
                new ServerInfo("srv-2", "rnd-openstack", "vm2", "com-1", "10.0.0.6", "ACTIVE")));

        List<CandidateServer> candidates = service.listCandidates(1L);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).serverId()).isEqualTo("srv-2");
    }

    @Test
    void add_servers_snapshots_info_and_skips_duplicates() {
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group()));
        when(memberRepository.existsByVmGroupIdAndServerId(1L, "srv-2")).thenReturn(false);
        when(memberRepository.existsByVmGroupIdAndServerId(1L, "srv-dup")).thenReturn(true);
        when(catalog.getServer("srv-2")).thenReturn(
                new ServerInfo("srv-2", "rnd-openstack", "vm2", "com-1", "10.0.0.6", "ACTIVE"));

        service.addServers(1L, List.of("srv-2", "srv-dup"), "admin", "ip");

        ArgumentCaptor<VmGroupMember> cap = ArgumentCaptor.forClass(VmGroupMember.class);
        verify(memberRepository).save(cap.capture()); // srv-dup 은 스킵
        assertThat(cap.getValue().getServerId()).isEqualTo("srv-2");
        assertThat(cap.getValue().getVmName()).isEqualTo("vm2");
        assertThat(cap.getValue().getIp()).isEqualTo("10.0.0.6");
    }

    @Test
    void list_servers_fills_live_status_and_connection_endpoints() {
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group()));
        when(memberRepository.findByVmGroupIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(
                new VmGroupMember(1L, "srv-1", "vm1", "com-1", "10.0.0.5")));
        when(catalog.statusByServerId()).thenReturn(Map.of("srv-1", "ACTIVE"));
        when(connectionRepository.findByVmGroupId(1L)).thenReturn(List.of(
                new VmGroupConnection(1L, "srv-1", "10.0.0.5:8000", "srv-2", "10.0.0.6:1")));

        List<GroupServer> servers = service.listServers(1L);

        assertThat(servers).hasSize(1);
        assertThat(servers.get(0).status()).isEqualTo("ACTIVE");
        assertThat(servers.get(0).connections()).containsExactly("10.0.0.5:8000");
    }

    @Test
    void list_servers_marks_deleted_when_status_missing() {
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group()));
        when(memberRepository.findByVmGroupIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(
                new VmGroupMember(1L, "gone", "vmX", "com-1", "10.0.0.9")));
        when(catalog.statusByServerId()).thenReturn(Map.of()); // OpenStack에서 사라짐

        List<GroupServer> servers = service.listServers(1L);
        assertThat(servers.get(0).status()).isEqualTo("Deleted");
    }

    @Test
    void save_network_map_replaces_connections_and_records_history() {
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group()));
        when(memberRepository.findByVmGroupIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());
        when(catalog.statusByServerId()).thenReturn(Map.of());

        service.saveNetworkMap(1L, List.of(
                new EdgeRequest("srv-1", "10.0.0.5:8000", "srv-2", "10.0.0.6:1")), "admin", "ip");

        verify(connectionRepository).deleteByVmGroupId(1L);
        verify(connectionRepository).save(any(VmGroupConnection.class));
        ArgumentCaptor<VmGroupActionHistory> cap = ArgumentCaptor.forClass(VmGroupActionHistory.class);
        verify(historyRepository).save(cap.capture());
        assertThat(cap.getValue().getAction()).isEqualTo(VmGroupActionHistory.Action.CONNECTION_UPDATE);
    }

    @Test
    void remove_server_also_removes_its_connections() {
        VmGroupMember member = new VmGroupMember(1L, "srv-1", "vm1", "com-1", "10.0.0.5");
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group()));
        when(memberRepository.findById(10L)).thenReturn(Optional.of(member));
        when(connectionRepository.findByVmGroupId(1L)).thenReturn(List.of(
                new VmGroupConnection(1L, "srv-1", "a", "srv-2", "b"),
                new VmGroupConnection(1L, "srv-3", "c", "srv-4", "d")));

        service.removeServer(1L, 10L, "admin", "ip");

        verify(connectionRepository).deleteAll(argThat(list -> {
            java.util.List<?> l = (java.util.List<?>) list;
            return l.size() == 1;
        }));
        verify(memberRepository).delete(member);
    }

    @Test
    void get_group_not_found() {
        when(groupRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getGroupSummary(99L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private static <T> T argThat(org.mockito.ArgumentMatcher<T> m) {
        return org.mockito.ArgumentMatchers.argThat(m);
    }
}
