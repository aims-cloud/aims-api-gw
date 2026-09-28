package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.util.*;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.LokiQueryClient;
import com.innogrid.aims.monitoring.LokiQueryClient.Point;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.*;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NodeLogQueriesTest {
    LokiQueryClient loki = mock(LokiQueryClient.class);
    NodeLogQueries queries = new NodeLogQueries(loki);
    ScopedHost host = new ScopedHost("ADMIN", 1L, "node", null, "mdc-1", null, "10.0.0.1:9100", List.of());
    Instant start = Instant.parse("2026-09-11T00:00:00Z");

    @Test void selectorEscapesRegexAndLogqlIndependently() {
        String selector = NodeLogQueries.selector(host, List.of("/var/a.log", "/var/b|c.log"), null);
        assertThat(selector).contains("instance=\"10.0.0.1:9100\"", "mdc_id=\"mdc-1\"", "a\\\\.log", "b\\\\|c\\\\.log");
        assertThat(NodeLogQueries.selector(host, List.of("/var/a\".log"), null)).contains("filename=\"/var/a\\\".log\"");
        assertThat(NodeLogQueries.quote("x\"\\\n")).isEqualTo("\"x\\\"\\\\\\n\"");
    }

    @Test void missingLogsAreNotMissingConfigurationAndFailuresAreUnknown() {
        when(loki.instant(anyString(), any())).thenReturn(List.of());
        assertThat(queries.recent(host, List.of("/a")).status(List.of("/a"))).isEqualTo(LogStatus.NO_RECENT_LOGS);
        when(loki.instant(anyString(), any())).thenThrow(new BusinessException(HttpStatus.BAD_GATEWAY, "down"));
        assertThat(queries.recent(host, List.of("/a")).status(List.of("/a"))).isEqualTo(LogStatus.UNKNOWN);
        assertThatThrownBy(() -> queries.fileCounts(host, List.of("/a"), NodeLogQueries.window(start, start.plusSeconds(60))))
                .isInstanceOf(BusinessException.class);
    }

    @Test void missingMappingOrNoPathsNeverQueriesLoki() {
        ScopedHost unmapped = new ScopedHost("ADMIN", 1L, "node", null, "mdc-1", null, null, List.of());
        assertThat(queries.recent(unmapped, List.of("/a")).status(List.of("/a"))).isEqualTo(LogStatus.UNCONFIGURED);
        queries.recent(host, List.of());
        verifyNoInteractions(loki);
    }

    @Test void normalizesWarningsAndRetainsUnclassifiedCounts() {
        when(loki.instant(anyString(), any())).thenReturn(List.of(
                new Point(Map.of("level", "WARN"), start, 2), new Point(Map.of("level", "warning"), start, 3),
                new Point(Map.of(), start, 7), new Point(Map.of("level", "TRACE"), start, 1)));
        Map<ServiceLogLevel, Long> counts = queries.levelCounts(host, List.of("/a"), NodeLogQueries.window(start, start.plusSeconds(60)));
        assertThat(counts).containsEntry(ServiceLogLevel.WARNING, 5L).containsEntry(ServiceLogLevel.UNKNOWN, 8L);
        assertThat(NodeLogQueries.selector(host, List.of("/a"), List.of(ServiceLogLevel.UNKNOWN)))
                .contains("level!~\"(?i:info|warn|warning|error|debug)\"");
    }

    @Test void trendUsesBucketWindowAndPartialTailAndZeroFills() {
        when(loki.range(anyString(), any(), any(), anyLong())).thenReturn(List.of(
                new Point(Map.of("level", "WARN"), start.plusSeconds(60), 2)));
        when(loki.instant(anyString(), any())).thenReturn(List.of(new Point(Map.of(), start.plusSeconds(150), 3)));
        ServiceLogTrendResponse result = queries.trend(host, List.of("/a"), NodeLogQueries.window(start, start.plusSeconds(150)), "1m");
        assertThat(result.buckets()).hasSize(3);
        assertThat(result.buckets().get(0).timestamp()).isEqualTo(start.getEpochSecond());
        assertThat(result.buckets().get(0).warningCount()).isEqualTo(2);
        assertThat(result.buckets().get(1).warningCount()).isZero();
        assertThat(result.buckets().get(2).unknownCount()).isEqualTo(3);
        verify(loki).range(contains("[60s]"), eq(start.plusSeconds(60)), eq(start.plusSeconds(120)), eq(60L));
        verify(loki).instant(contains("[30s]"), eq(start.plusSeconds(150)));
    }

    @Test void countIsIndependentOfLimitAndStreamsAreGloballySorted() {
        Instant end = start.plusSeconds(60);
        when(loki.instant(anyString(), any())).thenReturn(List.of(new Point(Map.of(), end, 100)));
        when(loki.logs(anyString(), any(), any(), anyInt())).thenReturn(List.of(
                new LokiQueryClient.LogLine(Map.of("filename", "/a"), start.plusSeconds(1), "old"),
                new LokiQueryClient.LogLine(Map.of("filename", "/a", "level", "warn"), start.plusSeconds(59), "new")));
        ServiceLogListResponse result = queries.search(host, List.of("/a"), List.of(ServiceLogLevel.WARNING), "\"oops", NodeLogQueries.window(start, end), 1);
        assertThat(result.totalCount()).isEqualTo(100);
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().get(0).message()).isEqualTo("new");
        verify(loki).logs(contains("|= \"\\\"oops\""), eq(start.plusNanos(1)), eq(end.plusNanos(1)), eq(1));
    }

    @Test void thirtyDayBoundaryMatchesLokiLimit() {
        assertThat(NodeLogQueries.window(start, start.plusSeconds(30L * 86400)).seconds()).isEqualTo(720L * 3600);
        assertThatThrownBy(() -> NodeLogQueries.window(start, start.plusSeconds(30L * 86400 + 1)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> NodeLogQueries.window(start, start.plusSeconds(31L * 86400)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(loki);
    }
    @Test void excessiveRangeAndBucketsAreRejectedBeforeRemoteCalls() {
        assertThatThrownBy(() -> NodeLogQueries.window(start, start)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> NodeLogQueries.window(start, start.plusSeconds(32 * 86400))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> queries.trend(host, List.of("/a"), NodeLogQueries.window(start, start.plusSeconds(86400)), "1m"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(loki);
    }
    @Test void recentFilesAggregatesByJobAndFilenameOverLookbackAndSkipsUnmapped() {
        when(loki.instant(anyString(), any())).thenReturn(List.of(
                new Point(Map.of("job", "syslog", "filename", "/var/log/syslog"), Instant.EPOCH, 10),
                new Point(Map.of("filename", "/var/log/orphan.log"), Instant.EPOCH, 2),
                new Point(Map.of("job", "empty"), Instant.EPOCH, 1)));
        List<NodeLogQueries.DiscoveredFile> result = queries.recentFiles(host, 86_400L);
        assertThat(result).containsExactly(
                new NodeLogQueries.DiscoveredFile("syslog", "/var/log/syslog", 10),
                new NodeLogQueries.DiscoveredFile(null, "/var/log/orphan.log", 2));
        verify(loki).instant(eq("sum by (job, filename) (count_over_time({instance=\"10.0.0.1:9100\", mdc_id=\"mdc-1\"}[86400s]))"), any());

        ScopedHost unmapped = new ScopedHost("ADMIN", 1L, "node", null, "mdc-1", null, null, List.of());
        assertThat(queries.recentFiles(unmapped, 86_400L)).isEmpty();
        verifyNoMoreInteractions(loki);
    }
}
