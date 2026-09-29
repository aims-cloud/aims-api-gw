package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.util.*;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.LogStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogFileResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceRequest;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceValidationResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceValidationResponse.ObservationStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectCapability;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.DiscoveredServiceResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.DiscoveredServiceResponse.DiscoveredSource;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceUnitState;
import com.innogrid.aims.monitoring.ResourceExplorerProperties;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostServiceStatusMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostServiceStatusMetricsQueryService.Unit;
import com.innogrid.aims.monitoring.entity.CollectService;
import com.innogrid.aims.monitoring.log.*;
import com.innogrid.aims.monitoring.repository.CollectServiceRepository;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ResourceExplorerNodeLogServiceTest {
    NodeAccessService access = mock(NodeAccessService.class);
    CollectServiceRepository repository = mock(CollectServiceRepository.class);
    NodeLogQueries queries = mock(NodeLogQueries.class);
    PortalLogContextResolver users = mock(PortalLogContextResolver.class);
    HostServiceStatusMetricsQueryService systemd = mock(HostServiceStatusMetricsQueryService.class);
    ResourceExplorerNodeLogService service = new ResourceExplorerNodeLogService(access, repository, queries, users, systemd,
            new ResourceExplorerProperties());
    ScopedHost host = new ScopedHost("ADMIN", 1L, "node", null, "mdc-1", null, "10.0.0.1:9100", List.of());
    Asset asset = new Asset("mdc-1", Asset.AssetType.GPU_SERVER, "node");
    Instant start = Instant.parse("2026-09-11T00:00:00Z");

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(asset, "id", 1L);
        when(access.require("1")).thenReturn(new NodeAccessService.AccessibleNode(asset, host));
    }
    CollectServiceRequest request(String name, String... paths) {
        return new CollectServiceRequest(name, "description", new CollectServiceRequest.LogCollection(true, false, List.of(paths)), null);
    }
    CollectService registered(long id, String name, String... paths) {
        CollectService row = new CollectService(asset, "user@example.com");
        row.update(request(name, paths));
        ReflectionTestUtils.setField(row, "id", id);
        return row;
    }

    @Test void scopedOutNodeNeverTouchesRegistryOrLoki() {
        when(access.require("2")).thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "missing"));
        assertThatThrownBy(() -> service.searchLogs("2", "10", null, null, null, start, start.plusSeconds(60), 10))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(repository, queries);
    }
    @Test void anotherNodesServiceIs404BeforeLoki() {
        when(repository.findByIdAndAsset_Id(10L, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getLogSummary("1", "10", null, start, start.plusSeconds(60)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(queries);
    }
    @Test void filesOutsideRegistrationAreRejected() {
        when(repository.findByIdAndAsset_Id(10L, 1L)).thenReturn(Optional.of(registered(10, "service", "/a")));
        assertThatThrownBy(() -> service.searchLogs("1", "10", List.of("/secret"), null, null, start, start.plusSeconds(60), 10))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(queries);
    }
    @Test void listObservesOnceAndKeepsRuntimeFieldsUnknown() {
        when(repository.findByAsset_Id(1L)).thenReturn(List.of(registered(10, "a", "/a"), registered(11, "b", "/b")));
        when(queries.recent(eq(host), anyCollection())).thenReturn(new NodeLogQueries.Observation(true, false, Map.of("/a", 3L)));
        PageResponse<CollectServiceResponse> result = service.getCollectServices("1", null, 1, 10, "serviceName,asc");
        assertThat(result.items().get(0).logStatus()).isEqualTo(LogStatus.RECENT_LOGS);
        assertThat(result.items().get(1).logStatus()).isEqualTo(LogStatus.NO_RECENT_LOGS);
        assertThat(result.items().get(0).status()).isNull();
        assertThat(result.items().get(0).pid()).isNull();
        assertThat(result.items().get(0).capabilities()).isEmpty();
        verify(queries, times(1)).recent(eq(host), anyCollection());
    }
    @Test void createSavesWithoutLokiAndUsesAuthenticatedCreator() {
        when(users.resolve()).thenReturn(new PortalLogContext("user@example.com", null, null, null, null, null));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> {
            CollectService row = (CollectService) inv.getArgument(0);
            ReflectionTestUtils.setField(row, "id", 10L);
            assertThat(row.getCreatedBy()).isEqualTo("user@example.com");
            return row;
        });
        CollectServiceResponse result = service.createCollectService("1", request(" name ", "/a"));
        assertThat(result.serviceName()).isEqualTo("name");
        assertThat(result.logStatus()).isEqualTo(LogStatus.UNKNOWN);
        verifyNoInteractions(queries);
    }
    @Test void duplicateNameReturnsConflict() {
        when(users.resolve()).thenReturn(new PortalLogContext("user@example.com", null, null, null, null, null));
        when(repository.existsByAsset_IdAndServiceNameAndIdNot(1L, "name", -1L)).thenReturn(true);
        assertThatThrownBy(() -> service.createCollectService("1", request("name", "/a")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(repository, never()).saveAndFlush(any());
    }
    @Test void updateCanDisableAllAndReplacePathsWithoutCollectorCalls() {
        CollectService row = registered(10, "name", "/old");
        when(repository.findByIdAndAsset_Id(10L, 1L)).thenReturn(Optional.of(row));
        CollectServiceRequest request = new CollectServiceRequest("new", null, new CollectServiceRequest.LogCollection(false, false, List.of("/new")),
                new CollectServiceRequest.TraceCollection(false, List.of(8080)));
        CollectServiceResponse result = service.updateCollectService("1", "10", request);
        assertThat(result.logCollection().logPaths()).containsExactly("/new");
        assertThat(result.logCollection().enabled()).isFalse();
        assertThat(result.traceCollection().ports()).containsExactly(8080);
        verifyNoInteractions(queries);
    }
    @Test void deleteOnlyRemovesScopedRegistry() {
        CollectService row = registered(10, "name", "/a");
        when(repository.findByIdAndAsset_Id(10L, 1L)).thenReturn(Optional.of(row));
        service.deleteCollectService("1", "10");
        verify(repository).delete(row);
        verifyNoInteractions(queries);
    }
    @Test void validatorsRejectGlobsAndOutOfRangePorts() {
        assertThatThrownBy(() -> ResourceExplorerNodeLogService.validate(request("name", "/var/*.log"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ResourceExplorerNodeLogService.validate(new CollectServiceRequest("name", null, null,
                new CollectServiceRequest.TraceCollection(true, List.of(65536))))).isInstanceOf(BusinessException.class);
        assertThatCode(() -> ResourceExplorerNodeLogService.validate(new CollectServiceRequest("name", null, null, null))).doesNotThrowAnyException();
    }
    @Test void summaryFileFilterDoesNotChangeServiceStatus() {
        CollectService row = registered(10, "name", "/active", "/quiet");
        when(repository.findByIdAndAsset_Id(10L, 1L)).thenReturn(Optional.of(row));
        when(queries.levelCounts(eq(host), eq(List.of("/quiet")), any())).thenReturn(Map.of());
        when(queries.recent(eq(host), eq(row.getLogPaths())))
                .thenReturn(new NodeLogQueries.Observation(true, false, Map.of("/active", 5L)));
        ServiceLogSummaryResponse result = service.getLogSummary("1", "10", "/quiet", start, start.plusSeconds(60));
        assertThat(result.totalCount()).isZero();
        assertThat(result.collectStatus()).isEqualTo(LogStatus.RECENT_LOGS);
        verify(queries).recent(host, row.getLogPaths());
    }
    @Test void traceAccessReusesNodeAndServiceOwnershipChecks() {
        when(repository.findByIdAndAsset_Id(10L, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requireAccessibleService("1", "10"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        when(access.require("2")).thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "node"));
        assertThatThrownBy(() -> service.requireAccessibleService("2", "10")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(queries);
    }
    @Test void registeredButEmptyFilesAreShownWithFullPath() {
        CollectService row = registered(10, "name", "/one/app.log", "/two/app.log");
        when(repository.findByIdAndAsset_Id(10L, 1L)).thenReturn(Optional.of(row));
        when(queries.fileCounts(eq(host), anyList(), any())).thenReturn(Map.of("/one/app.log", 7L));
        List<ServiceLogFileResponse> result = service.getLogFiles("1", "10", start, start.plusSeconds(60));
        assertThat(result).hasSize(2);
        assertThat(result.get(1).path()).isEqualTo("/two/app.log");
        assertThat(result.get(1).totalCount()).isZero();
    }

    // ── 후보 탐지 ───────────────────────────────────────────────

    @Test void discoveredGroupsRecentLokiFilesByJobAndSortsByName() {
        when(queries.recentFiles(host, 86_400L)).thenReturn(List.of(
                new NodeLogQueries.DiscoveredFile("syslog", "/var/log/syslog", 10),
                new NodeLogQueries.DiscoveredFile("aims-app", "/var/log/aims/error.log", 1),
                new NodeLogQueries.DiscoveredFile("aims-app", "/var/log/aims/app.log", 3),
                new NodeLogQueries.DiscoveredFile(null, "/var/log/orphan.log", 2)));
        List<DiscoveredServiceResponse> result = service.getDiscoveredServices("1");
        assertThat(result).extracting(DiscoveredServiceResponse::serviceName).containsExactly("aims-app", "orphan.log", "syslog");
        assertThat(result.get(0).description()).isEqualTo("최근 24시간 수집 파일: /var/log/aims/app.log (3건), /var/log/aims/error.log (1건)");
        assertThat(result.get(0).capabilities()).containsExactly(CollectCapability.LOG);
        assertThat(result.get(0).pid()).isNull();
        assertThat(result.get(0).port()).isNull();
        assertThat(result).extracting(DiscoveredServiceResponse::source).containsOnly(DiscoveredSource.LOKI);
        assertThat(result.get(0).suggestedLogPaths()).containsExactly("/var/log/aims/app.log", "/var/log/aims/error.log");
        assertThat(result.get(2).suggestedLogPaths()).containsExactly("/var/log/syslog");
    }
    @Test void discoveredIsEmptyWithoutLabelMappingAndNeverQueriesLoki() {
        ScopedHost unmapped = new ScopedHost("ADMIN", 1L, "node", null, "mdc-1", null, null, List.of());
        when(access.require("1")).thenReturn(new NodeAccessService.AccessibleNode(asset, unmapped));
        assertThat(service.getDiscoveredServices("1")).isEmpty();
        verifyNoInteractions(queries, systemd);
    }
    @Test void discoveredAddsActiveSystemdServiceUnitsAfterLokiAndFiltersNoise() {
        when(queries.recentFiles(host, 86_400L)).thenReturn(List.of(
                new NodeLogQueries.DiscoveredFile("syslog", "/var/log/syslog", 10)));
        when(systemd.units(any())).thenReturn(List.of(
                new Unit("nginx.service", "forking", ServiceUnitState.ACTIVE),
                new Unit("syslog.service", "simple", ServiceUnitState.ACTIVE),          // Loki 후보와 이름 겹침 → Loki 우선
                new Unit("postgresql.service", "notify", ServiceUnitState.INACTIVE),   // 비활성 제외
                new Unit("systemd-journald.service", "notify", ServiceUnitState.ACTIVE), // 제외 패턴
                new Unit("docker.socket", "", ServiceUnitState.ACTIVE),                  // .service 아님
                new Unit("Docker.service", null, ServiceUnitState.ACTIVE)));
        List<DiscoveredServiceResponse> result = service.getDiscoveredServices("1");
        assertThat(result).extracting(DiscoveredServiceResponse::serviceName).containsExactly("Docker", "nginx", "syslog");
        assertThat(result).extracting(DiscoveredServiceResponse::source)
                .containsExactly(DiscoveredSource.SYSTEMD, DiscoveredSource.SYSTEMD, DiscoveredSource.LOKI);
        assertThat(result.get(1).description()).isEqualTo("systemd 유닛 nginx.service (active, forking) — 기본 로그 경로 제안 2건");
        assertThat(result.get(1).suggestedLogPaths()).containsExactly("/var/log/nginx/access.log", "/var/log/nginx/error.log");
        assertThat(result.get(0).description()).isEqualTo("systemd 유닛 Docker.service (active) — 로그 경로는 2단계에서 입력");
        assertThat(result.get(0).suggestedLogPaths()).isEmpty();                                  // journald 전용, 프리셋 없음
        assertThat(result.get(2).description()).startsWith("최근 24시간 수집 파일");
    }
    @Test void logPathPresetsMatchUnitNamesCaseInsensitivelyAndIgnoreInstanceSuffix() {
        assertThat(LogPathPresets.forUnit("NGINX.service")).containsExactly("/var/log/nginx/access.log", "/var/log/nginx/error.log");
        assertThat(LogPathPresets.forUnit("postgresql@16-main.service")).contains("/var/log/postgresql/postgresql-16-main.log");
        assertThat(LogPathPresets.forUnit("httpd")).containsExactly("/var/log/httpd/access_log", "/var/log/httpd/error_log");
        assertThat(LogPathPresets.forUnit("containerd.service")).isEmpty();
        assertThat(LogPathPresets.forUnit(null)).isEmpty();
    }
    @Test void discoveredUsesSystemdOnlyWhenLokiMappingMissingButExporterTargetExists() {
        ScopedHost noMdc = new ScopedHost("ADMIN", 1L, "node", null, null, null, "10.0.0.1:9100", List.of());
        when(access.require("1")).thenReturn(new NodeAccessService.AccessibleNode(asset, noMdc));
        when(systemd.units(any())).thenReturn(List.of(new Unit("nginx.service", "simple", ServiceUnitState.ACTIVE)));
        List<DiscoveredServiceResponse> result = service.getDiscoveredServices("1");
        assertThat(result).extracting(DiscoveredServiceResponse::serviceName).containsExactly("nginx");
        verifyNoInteractions(queries);
    }
    @Test void discoveredPropagatesLokiFailureInsteadOfEmptyList() {
        when(queries.recentFiles(host, 86_400L)).thenThrow(new BusinessException(HttpStatus.BAD_GATEWAY, "down"));
        assertThatThrownBy(() -> service.getDiscoveredServices("1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    // ── 위저드 검증 ─────────────────────────────────────────────

    @Test void validateCollectsIssuesPerFieldAndSkipsObservationWhenInvalid() {
        CollectServiceRequest request = new CollectServiceRequest(" ", "d",
                new CollectServiceRequest.LogCollection(false, true, List.of("/var/*.log")),
                new CollectServiceRequest.TraceCollection(true, List.of(0)));
        CollectServiceValidationResponse result = service.validateCollectService("1", request);
        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).extracting(CollectServiceValidationResponse.Issue::field)
                .containsExactly("serviceName", "logCollection.syslogEnabled", "logCollection.logPaths", "traceCollection.ports");
        assertThat(result.observations()).isEmpty();
        verifyNoInteractions(queries);
    }
    @Test void validateObservesPathsAndTreatsMissingLogsAsInformational() {
        when(queries.fileCounts(eq(host), eq(List.of("/a", "/b")), any())).thenReturn(Map.of("/a", 5L));
        CollectServiceValidationResponse result = service.validateCollectService("1", request("name", "/b", "/a", "/a"));
        assertThat(result.valid()).isTrue();
        assertThat(result.issues()).isEmpty();
        assertThat(result.observations()).extracting(CollectServiceValidationResponse.PathObservation::path).containsExactly("/a", "/b");
        assertThat(result.observations()).extracting(CollectServiceValidationResponse.PathObservation::status)
                .containsExactly(ObservationStatus.OBSERVED, ObservationStatus.NOT_OBSERVED);
        verify(repository, never()).existsByAsset_IdAndServiceNameAndIdNot(anyLong(), anyString(), anyLong());
    }
    @Test void validateLokiFailureOrMissingMappingIsUnknownButStillValid() {
        when(queries.fileCounts(eq(host), any(), any())).thenThrow(new BusinessException(HttpStatus.BAD_GATEWAY, "down"));
        CollectServiceValidationResponse result = service.validateCollectService("1", request("name", "/a"));
        assertThat(result.valid()).isTrue();
        assertThat(result.observations()).extracting(CollectServiceValidationResponse.PathObservation::status).containsExactly(ObservationStatus.UNKNOWN);

        ScopedHost unmapped = new ScopedHost("ADMIN", 1L, "node", null, "mdc-1", null, null, List.of());
        when(access.require("1")).thenReturn(new NodeAccessService.AccessibleNode(asset, unmapped));
        result = service.validateCollectService("1", request("name", "/a"));
        assertThat(result.valid()).isTrue();
        assertThat(result.observations()).extracting(CollectServiceValidationResponse.PathObservation::status).containsExactly(ObservationStatus.UNKNOWN);
        verify(queries, times(1)).fileCounts(any(), any(), any());
    }
    @Test void validateWithoutLogPathsHasNoObservations() {
        CollectServiceValidationResponse result = service.validateCollectService("1",
                new CollectServiceRequest("name", null, null, new CollectServiceRequest.TraceCollection(true, List.of(8080))));
        assertThat(result.valid()).isTrue();
        assertThat(result.observations()).isEmpty();
        verifyNoInteractions(queries);
    }
}
