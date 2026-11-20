package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.ResourceExplorerProperties;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.LogStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogListResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.AgentTargetsStatusResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.AgentTargetsStatusResponse.TargetsStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceRequest;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.DiscoveredServiceResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.DiscoveredServiceResponse.DiscoveredSource;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceUnitState;
import com.innogrid.aims.monitoring.entity.AgentTransport;
import com.innogrid.aims.monitoring.entity.CollectOwner;
import com.innogrid.aims.monitoring.entity.CollectService;
import com.innogrid.aims.monitoring.log.PortalLogContext;
import com.innogrid.aims.monitoring.log.PortalLogContextResolver;
import com.innogrid.aims.monitoring.repository.CollectServiceRepository;
import com.innogrid.aims.monitoring.service.agent.AgentTargetsService;
import com.innogrid.aims.monitoring.service.agent.CollectServicesChangedEvent;
import com.innogrid.aims.monitoring.service.resourceexplorer.node.NodeAccessService;
import com.innogrid.aims.monitoring.service.resourceexplorer.node.NodeLogQueries;
import com.innogrid.aims.monitoring.service.resourceexplorer.node.ResourceExplorerNodeLogService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostServiceStatusMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostServiceStatusMetricsQueryService.Unit;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * VM 로그 탭 — 노드와 같은 공통 로직을 쓰되 원장 소유자·Loki 라벨·systemd 선택자가 모두 Tumblebug 키인지,
 * 스코프 밖 VM 은 원장·Loki 를 건드리기 전에 404 인지 검증한다. 대역: ns-1 / infra-1 / vm-1.
 */
class ResourceExplorerVmLogServiceTest {
    TumblebugPanelSupport support = mock(TumblebugPanelSupport.class);
    CollectServiceRepository repository = mock(CollectServiceRepository.class);
    NodeLogQueries queries = mock(NodeLogQueries.class);
    PortalLogContextResolver users = mock(PortalLogContextResolver.class);
    HostServiceStatusMetricsQueryService systemd = mock(HostServiceStatusMetricsQueryService.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    AgentTargetsService agentTargets = mock(AgentTargetsService.class);
    ResourceExplorerNodeLogService logs = new ResourceExplorerNodeLogService(mock(NodeAccessService.class), repository, queries,
            users, systemd, new ResourceExplorerProperties(), events, agentTargets);
    ResourceExplorerVmLogService service = new ResourceExplorerVmLogService(support, logs);

    CollectOwner vm = CollectOwner.vm("ns-1", "infra-1", "vm-1");
    VmLogScope scope = new VmLogScope("ns-1", "infra-1", "vm-1");
    Instant start = Instant.parse("2026-10-06T00:00:00Z");

    CollectServiceRequest request(String name, String... paths) {
        return new CollectServiceRequest(name, null, new CollectServiceRequest.LogCollection(true, false, List.of(paths)), null);
    }

    CollectService registered(long id, String name, String... paths) {
        CollectService row = new CollectService(vm, "user@example.com");
        row.update(request(name, paths));
        ReflectionTestUtils.setField(row, "id", id);
        return row;
    }

    @Test void outOfScopeVmIs404BeforeRegistryOrLoki() {
        when(support.accessibleNode(null, "ns-1", "infra-1", "vm-x"))
                .thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "가상머신을 찾을 수 없습니다"));
        assertThatThrownBy(() -> service.getCollectServices(null, "ns-1", "infra-1", "vm-x", null, 1, 10, "serviceName,asc"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.searchLogs(null, "ns-1", "infra-1", "vm-x", "10", null, null, null, start, start.plusSeconds(60), 10))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(repository, queries, events);
    }

    @Test void listReadsVmOwnedRowsAndObservesWithTumblebugLabels() {
        when(repository.findByTbNsAndTbInfraAndTbNode("ns-1", "infra-1", "vm-1"))
                .thenReturn(List.of(registered(10, "nginx", "/var/log/nginx/access.log"), registered(11, "app", "/opt/app.log")));
        when(queries.recent(eq(scope), anyCollection()))
                .thenReturn(new NodeLogQueries.Observation(true, false, Map.of("/var/log/nginx/access.log", 4L)));

        PageResponse<CollectServiceResponse> page = service.getCollectServices(null, "ns-1", "infra-1", "vm-1", null, 1, 10, "serviceName,asc");

        assertThat(page.items()).extracting(CollectServiceResponse::serviceName).containsExactly("app", "nginx");
        assertThat(page.items().get(1).logStatus()).isEqualTo(LogStatus.RECENT_LOGS);
        assertThat(page.items().get(0).logStatus()).isEqualTo(LogStatus.NO_RECENT_LOGS);
        verify(repository, never()).findByAsset_Id(anyLong());
    }

    @Test void createStoresTumblebugKeysAndTriggersVmAgentReflection() {
        when(users.resolve()).thenReturn(new PortalLogContext("user@example.com", null, null, null, null, null));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> {
            CollectService row = inv.getArgument(0);
            assertThat(row.getAsset()).isNull();
            assertThat(row.getTbNs()).isEqualTo("ns-1");
            assertThat(row.getTbInfra()).isEqualTo("infra-1");
            assertThat(row.getTbNode()).isEqualTo("vm-1");
            ReflectionTestUtils.setField(row, "id", 20L);
            return row;
        });

        CollectServiceResponse created = service.createCollectService(null, "ns-1", "infra-1", "vm-1", request("nginx", "/var/log/nginx/access.log"));

        assertThat(created.serviceId()).isEqualTo("20");
        assertThat(created.logStatus()).isEqualTo(LogStatus.UNKNOWN);       // VM 라벨 매핑이 있으니 미설정이 아니다
        verify(events).publishEvent(new CollectServicesChangedEvent(vm));
        verify(repository).existsByTbNsAndTbInfraAndTbNodeAndServiceNameAndIdNot("ns-1", "infra-1", "vm-1", "nginx", -1L);
        verify(repository, never()).existsByAsset_IdAndServiceNameAndIdNot(anyLong(), anyString(), anyLong());
    }

    @Test void duplicateNameOnSameVmIsConflict() {
        when(users.resolve()).thenReturn(new PortalLogContext("user@example.com", null, null, null, null, null));
        when(repository.existsByTbNsAndTbInfraAndTbNodeAndServiceNameAndIdNot("ns-1", "infra-1", "vm-1", "nginx", -1L)).thenReturn(true);
        assertThatThrownBy(() -> service.createCollectService(null, "ns-1", "infra-1", "vm-1", request("nginx", "/a")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test void discoveryUsesTumblebugSelectorsForLokiAndSystemd() {
        when(queries.recentFiles(scope, 86_400L)).thenReturn(List.of(new NodeLogQueries.DiscoveredFile("syslog", "/var/log/syslog", 9)));
        when(systemd.units(MetricSelector.tumblebugNode("ns-1", "infra-1", "vm-1")))
                .thenReturn(List.of(new Unit("nginx.service", "notify", ServiceUnitState.ACTIVE)));

        List<DiscoveredServiceResponse> candidates = service.getDiscoveredServices(null, "ns-1", "infra-1", "vm-1");

        assertThat(candidates).extracting(DiscoveredServiceResponse::serviceName).containsExactly("nginx", "syslog");
        assertThat(candidates.get(0).source()).isEqualTo(DiscoveredSource.SYSTEMD);
        assertThat(candidates.get(0).suggestedLogPaths()).contains("/var/log/nginx/access.log");
        assertThat(candidates.get(1).source()).isEqualTo(DiscoveredSource.LOKI);
    }

    @Test void logSearchIsScopedToTheVmsOwnService() {
        when(repository.findByIdAndTbNsAndTbInfraAndTbNode(10L, "ns-1", "infra-1", "vm-1"))
                .thenReturn(Optional.of(registered(10, "nginx", "/var/log/nginx/access.log")));
        when(queries.search(eq(scope), eq(List.of("/var/log/nginx/access.log")), any(), any(), any(), eq(5)))
                .thenReturn(new ServiceLogListResponse(1, List.of()));

        assertThat(service.searchLogs(null, "ns-1", "infra-1", "vm-1", "10", null, null, null, start, start.plusSeconds(60), 5).totalCount())
                .isEqualTo(1);

        when(repository.findByIdAndTbNsAndTbInfraAndTbNode(11L, "ns-1", "infra-1", "vm-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getLogFiles(null, "ns-1", "infra-1", "vm-1", "11", start, start.plusSeconds(60)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(repository, never()).findByIdAndAsset_Id(anyLong(), anyLong());
    }

    @Test void agentStatusIsLookedUpByVmOwner() {
        AgentTargetsStatusResponse status = new AgentTargetsStatusResponse(AgentTransport.PULL, TargetsStatus.APPLIED, null, 1, null);
        when(agentTargets.status(vm)).thenReturn(status);
        assertThat(service.getAgentTargetsStatus(null, "ns-1", "infra-1", "vm-1")).isSameAs(status);
    }
}
