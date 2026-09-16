package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMetric;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostFileSystemMetricsQueryService.MountUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** File system 패널 Thanos 조회 — 그룹/마운트 PromQL, 마운트 표본 결합(inode 미보고), 증가분, 시계열, 장애 처리. */
class HostFileSystemMetricsQueryServiceTest {

    private static final MetricSelector SEL = MetricSelector.instance("10.0.0.1:9100");
    private static final String INSTANCE = "instance=\"10.0.0.1:9100\"";
    private static final String FS = "fstype!~\"tmpfs|overlay\"";

    VictoriaMetricsClient client = mock(VictoriaMetricsClient.class);
    HostFileSystemMetricsQueryService service = new HostFileSystemMetricsQueryService(client);

    @Test
    void 그룹_PromQL은_tmpfs_overlay_제외_합산이고_Used와_Free가_Total을_이룬다() {
        String size = "sum by (instance) (node_filesystem_size_bytes{" + FS + "," + INSTANCE + "})";
        String avail = "sum by (instance) (node_filesystem_avail_bytes{" + FS + "," + INSTANCE + "})";
        assertThat(HostFileSystemMetricsQueryService.groupQuery(FileSystemMetric.FS_USED, SEL))
                .isEqualTo("(" + size + " - " + avail + ") / 1073741824");
        assertThat(HostFileSystemMetricsQueryService.groupQuery(FileSystemMetric.FS_USED_PERCENT, SEL))
                .isEqualTo("(1 - " + avail + " / " + size + ") * 100");
        assertThat(HostFileSystemMetricsQueryService.groupQuery(FileSystemMetric.FS_FREE_PERCENT, SEL))
                .isEqualTo(avail + " / " + size + " * 100");
        assertThat(HostFileSystemMetricsQueryService.groupQuery(FileSystemMetric.FS_INODE_USAGE_PERCENT, SEL))
                .isEqualTo("(1 - sum by (instance) (node_filesystem_files_free{" + FS + "," + INSTANCE + "})"
                        + " / sum by (instance) (node_filesystem_files{" + FS + "," + INSTANCE + "})) * 100");
    }

    @Test
    void 마운트_PromQL은_mountpoint_완전일치_매처를_쓰고_경로의_큰따옴표를_이스케이프한다() {
        assertThat(HostFileSystemMetricsQueryService.mountQuery(FileSystemMetric.FS_TOTAL, SEL, "/data"))
                .isEqualTo("node_filesystem_size_bytes{" + FS + ",mountpoint=\"/data\"," + INSTANCE + "} / 1073741824");
        assertThat(HostFileSystemMetricsQueryService.growthGbQuery(SEL, "/mnt/a\"b"))
                .isEqualTo("-delta(node_filesystem_avail_bytes{" + FS + ",mountpoint=\"/mnt/a\\\"b\"," + INSTANCE + "}[24h]) / 1073741824");
    }

    @Test
    void 마운트_표본은_size_기준으로_avail_files를_mountpoint로_결합하고_inode_미보고는_null이다() {
        when(client.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.startsWith("node_filesystem_size_bytes")) {
                return List.of(fs("/", "ext4", "/dev/sda1", "107374182400"), fs("/data", "xfs", "/dev/sdb1", "1099511627776"));
            }
            if (q.startsWith("node_filesystem_avail_bytes")) {
                return List.of(fs("/", "ext4", "/dev/sda1", "53687091200"), fs("/data", "xfs", "/dev/sdb1", "549755813888"));
            }
            if (q.startsWith("node_filesystem_files_free")) {
                return List.of(fs("/", "ext4", "/dev/sda1", "6000000"));
            }
            if (q.startsWith("node_filesystem_files")) {
                return List.of(fs("/", "ext4", "/dev/sda1", "6553600"), fs("/data", "xfs", "/dev/sdb1", "0"));
            }
            return List.of();
        });

        List<MountUsage> mounts = service.mounts(SEL);

        assertThat(mounts).hasSize(2);
        MountUsage root = mounts.get(0);
        assertThat(root.mountPath()).isEqualTo("/");
        assertThat(root.fsType()).isEqualTo("ext4");
        assertThat(root.device()).isEqualTo("/dev/sda1");
        assertThat(root.usedBytes()).isEqualTo(53687091200.0);
        assertThat(root.usedPercent()).isEqualTo(50.0);
        assertThat(root.inodeUsagePercent()).isCloseTo(8.45, org.assertj.core.data.Offset.offset(0.01));
        assertThat(root.collectedAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));

        MountUsage data = mounts.get(1);
        assertThat(data.mountPath()).isEqualTo("/data");
        assertThat(data.inodeUsagePercent()).isNull();                 // files=0, files_free 없음

        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        verify(client, org.mockito.Mockito.times(4)).queryResults(q.capture());
        assertThat(q.getAllValues()).allMatch(s -> s.endsWith("{" + FS + "," + INSTANCE + "}"));
    }

    @Test
    void 마운트_하나는_mountpoint_매처로_조회해_그_경로만_돌려주고_없으면_empty다() {
        when(client.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (!q.contains("mountpoint=\"/data\"")) return List.of();
            if (q.startsWith("node_filesystem_size_bytes")) return List.of(fs("/data", "xfs", "/dev/sdb1", "100"));
            if (q.startsWith("node_filesystem_avail_bytes")) return List.of(fs("/data", "xfs", "/dev/sdb1", "25"));
            return List.of();
        });

        Optional<MountUsage> data = service.mount(SEL, "/data");
        assertThat(data).isPresent();
        assertThat(data.get().usedPercent()).isEqualTo(75.0);
        assertThat(data.get().filesTotal()).isNull();

        assertThat(service.mount(SEL, "/missing")).isEmpty();
    }

    @Test
    void 증가분과_시계열은_값을_읽고_장애나_부재는_미수집이다() {
        when(client.queryResults(anyString())).thenReturn(vector("2.75"));
        assertThat(service.growth24hGb(SEL, "/")).isEqualTo(2.75);

        Instant from = Instant.ofEpochSecond(1_700_000_000L);
        Instant to = Instant.ofEpochSecond(1_700_003_600L);
        when(client.queryRangeResults(anyString(), any(), any(), anyString()))
                .thenReturn(List.of(range(List.of(List.of(1_700_000_000L, "12.34"), List.of(1_700_000_300L, "NaN"),
                        List.of(1_700_000_600L, "15.0")))));
        List<DataPoint> group = service.groupTrend(FileSystemMetric.FS_USED, SEL, from, to, "5m");
        assertThat(group).containsExactly(new DataPoint(1_700_000_000L, 12.3), new DataPoint(1_700_000_600L, 15.0));

        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        service.mountTrend(FileSystemMetric.FS_FREE, SEL, "/data", from, to, "5m");
        verify(client, org.mockito.Mockito.times(2)).queryRangeResults(q.capture(), eq(from), eq(to), eq("5m"));
        assertThat(q.getAllValues().get(1))
                .isEqualTo("node_filesystem_avail_bytes{" + FS + ",mountpoint=\"/data\"," + INSTANCE + "} / 1073741824");

        when(client.queryResults(anyString())).thenThrow(new RuntimeException("connection refused"));
        when(client.queryRangeResults(anyString(), any(), any(), anyString())).thenThrow(new RuntimeException("timeout"));
        assertThat(service.mounts(SEL)).isEmpty();
        assertThat(service.mount(SEL, "/")).isEmpty();
        assertThat(service.growth24hGb(SEL, "/")).isNull();
        assertThat(service.groupTrend(FileSystemMetric.FS_TOTAL, SEL, from, to, "5m")).isEmpty();
        assertThat(service.mountTrend(FileSystemMetric.FS_TOTAL, SEL, "/", from, to, "5m")).isEmpty();
    }

    // ── 대역 ────────────────────────────────────────────────────

    static Map<String, Object> fs(String mountpoint, String fstype, String device, String value) {
        return Map.of("metric", Map.of("mountpoint", mountpoint, "fstype", fstype, "device", device),
                "value", List.of(1_700_000_000L, value));
    }

    static List<Map<String, Object>> vector(String value) {
        return List.of(Map.of("metric", Map.of(), "value", List.of(1_700_000_000L, value)));
    }

    static Map<String, Object> range(List<List<Object>> values) {
        return Map.of("metric", Map.of(), "values", values);
    }
}
