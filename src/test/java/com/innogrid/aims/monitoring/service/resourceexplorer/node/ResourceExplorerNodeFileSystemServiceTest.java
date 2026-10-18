package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.repository.AssetRepository;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMountInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMountListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMountSummaryResponse;
import com.innogrid.aims.monitoring.repository.MonitoringTargetRepository;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostFileSystemMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostFileSystemMetricsQueryService.MountUsage;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 노드 File system 카테고리 패널 테스트 — 스코프 접근 검증(404), exporter 미등록 노드의 미수집 처리,
 * 그룹 KPI 집계(Near Full 90% 경계·최대 마운트 경로·inode null), 목록 정렬·페이징, 마운트 KPI·정보·404, interval 시계열.
 *
 * 대역: node-01(assets.id 11, node_exporter 10.0.0.1:9100) — ADMIN 스코프
 *       node-02(assets.id 12, exporter 없음) — ADMIN 스코프
 *       assets.id 19 — 스코프 밖
 *       마운트: / ext4 100GB 중 50GB 사용(inode 8.5%), /data xfs 1TB 중 900GB 사용(inode 미보고), /boot vfat 1GB 중 0.3GB 사용(inode 1%)
 */
class ResourceExplorerNodeFileSystemServiceTest {

    ResourceScopeService scopeService = mock(ResourceScopeService.class);
    AssetRepository assetRepository = mock(AssetRepository.class);
    HostFileSystemMetricsQueryService metrics = mock(HostFileSystemMetricsQueryService.class);

    ResourceExplorerNodeFileSystemService service = new ResourceExplorerNodeFileSystemService(
            new NodeAccessService(scopeService, assetRepository, mock(MonitoringTargetRepository.class)), metrics);

    static final MetricSelector NODE_SEL = MetricSelector.instance("10.0.0.1:9100");
    static final double GB = 1024.0 * 1024 * 1024;
    static final Instant SAMPLE_AT = Instant.ofEpochSecond(1_700_000_000L);

    static final MountUsage ROOT = new MountUsage("/", "ext4", "/dev/sda1", 100 * GB, 50 * GB, 6_553_600.0, 6_000_000.0, SAMPLE_AT);
    static final MountUsage DATA = new MountUsage("/data", "xfs", "/dev/sdb1", 1024 * GB, 124 * GB, 0.0, null, SAMPLE_AT);
    static final MountUsage BOOT = new MountUsage("/boot", "vfat", "/dev/sda2", 1 * GB, 0.7 * GB, 1000.0, 990.0, SAMPLE_AT);

    @BeforeEach
    void setUp() {
        Asset node01 = new Asset("mdc-01", Asset.AssetType.SERVER, "node-01");
        ReflectionTestUtils.setField(node01, "id", 11L);
        Asset node02 = new Asset("mdc-01", Asset.AssetType.SERVER, "node-02");
        ReflectionTestUtils.setField(node02, "id", 12L);

        ScopedHost withExporter = new ScopedHost("ADMIN", 11L, "node-01", null, "mdc-01", ResourceStatus.RUNNING,
                "10.0.0.1:9100", List.of("10.0.0.1"));
        ScopedHost withoutExporter = new ScopedHost("ADMIN", 12L, "node-02", null, "mdc-01", ResourceStatus.PENDING,
                null, List.of());
        when(scopeService.currentUserScope()).thenReturn(
                new Scope(Set.of("ADMIN"), List.of(withExporter, withoutExporter), List.of(), List.of()));
        when(assetRepository.findById(11L)).thenReturn(Optional.of(node01));
        when(assetRepository.findById(12L)).thenReturn(Optional.of(node02));

        when(metrics.mounts(NODE_SEL)).thenReturn(List.of(ROOT, DATA, BOOT));
    }

    // ── 접근 검증·미수집 ────────────────────────────────────────

    @Test
    void 스코프_밖_노드는_404이고_Thanos를_조회하지_않는다() {
        assertThatThrownBy(() -> service.getGroupSummary("19"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getMountInfo("19", "/")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getMountMetricTrend("19", "/", FileSystemMetric.FS_USED, NodeMetricInterval.LAST_1H))
                .isInstanceOf(BusinessException.class);

        verify(metrics, never()).mounts(any());
        verify(metrics, never()).mount(any(), anyString());
        verify(metrics, never()).mountTrend(any(), any(), anyString(), any(), any(), anyString());
    }

    @Test
    void exporter가_없는_노드는_Thanos를_조회하지_않고_미수집으로_내린다() {
        FileSystemGroupSummaryResponse summary = service.getGroupSummary("12");
        assertThat(summary.totalMountCount()).isZero();
        assertThat(summary.maxUsedPercentMountPath()).isNull();
        assertThat(summary.maxInodeUsagePercent()).isNull();
        assertThat(service.getMounts("12", 1, 10, "mountPath,asc").totalElements()).isZero();
        assertThat(service.getGroupMetricTrend("12", FileSystemMetric.FS_USED, NodeMetricInterval.LAST_1H).series()).isEmpty();

        assertThatThrownBy(() -> service.getMountSummary("12", "/"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(metrics, never()).mounts(any());
        verify(metrics, never()).mount(any(), anyString());
        verify(metrics, never()).groupTrend(any(), any(), any(), any(), anyString());
    }

    // ── 그룹 ────────────────────────────────────────────────────

    @Test
    void 그룹_KPI는_Near_Full_90퍼센트_경계와_최대_사용_마운트_경로_합계_최대_inode를_집계한다() {
        when(metrics.mounts(NODE_SEL)).thenReturn(List.of(ROOT, DATA, BOOT,
                new MountUsage("/edge", "ext4", "/dev/sdc1", 10 * GB, 1 * GB, null, null, SAMPLE_AT)));   // 정확히 90%

        FileSystemGroupSummaryResponse summary = service.getGroupSummary("11");

        assertThat(summary.totalMountCount()).isEqualTo(4);
        assertThat(summary.nearFullCount()).isEqualTo(1);                       // /edge 90.0% 만 (/data 는 87.9%)
        assertThat(summary.maxUsedPercent()).isEqualTo(90.0);
        assertThat(summary.maxUsedPercentMountPath()).isEqualTo("/edge");
        assertThat(summary.maxUsedGb()).isEqualTo(900.0);
        assertThat(summary.maxUsedGbMountPath()).isEqualTo("/data");
        assertThat(summary.totalSizeGb()).isEqualTo(1135.0);
        assertThat(summary.totalUsedGb()).isEqualTo(959.3);
        assertThat(summary.totalFreeGb()).isEqualTo(175.7);
        assertThat(summary.maxInodeUsagePercent()).isEqualTo(8.4);              // /data 는 미보고라 제외
    }

    @Test
    void 그룹_KPI는_inode를_보고하는_마운트가_없으면_Max_Inode가_null이다() {
        when(metrics.mounts(NODE_SEL)).thenReturn(List.of(DATA));

        FileSystemGroupSummaryResponse summary = service.getGroupSummary("11");

        assertThat(summary.nearFullCount()).isZero();
        assertThat(summary.maxInodeUsagePercent()).isNull();
    }

    @Test
    void 마운트_목록은_행을_GB_소수1자리로_채우고_정렬_페이징하며_미지원_필드는_400이다() {
        PageResponse<FileSystemMountListItemResponse> page = service.getMounts("11", 1, 10, "mountPath,asc");

        assertThat(page.totalElements()).isEqualTo(3);
        FileSystemMountListItemResponse root = page.items().get(0);
        assertThat(root.mountPath()).isEqualTo("/");
        assertThat(root.fsType()).isEqualTo("ext4");
        assertThat(root.totalGb()).isEqualTo(100.0);
        assertThat(root.usedPercent()).isEqualTo(50.0);
        assertThat(root.usedGb()).isEqualTo(50.0);
        assertThat(root.freeGb()).isEqualTo(50.0);
        assertThat(root.inodeUsagePercent()).isEqualTo(8.4);
        assertThat(page.items().get(2).inodeUsagePercent()).isNull();          // /data

        assertThat(service.getMounts("11", 1, 10, "usedPercent,desc").items())
                .extracting(FileSystemMountListItemResponse::mountPath).containsExactly("/data", "/", "/boot");
        assertThat(service.getMounts("11", 1, 10, "inodeUsagePercent,desc").items())
                .extracting(FileSystemMountListItemResponse::mountPath).containsExactly("/", "/boot", "/data");   // 값 없음은 마지막
        assertThat(service.getMounts("11", 1, 10, "inodeUsagePercent,asc").items())
                .extracting(FileSystemMountListItemResponse::mountPath).containsExactly("/boot", "/", "/data");

        PageResponse<FileSystemMountListItemResponse> second = service.getMounts("11", 2, 2, "totalGb,asc");
        assertThat(second.items()).extracting(FileSystemMountListItemResponse::mountPath).containsExactly("/data");
        assertThat(second.totalPages()).isEqualTo(2);

        assertThatThrownBy(() -> service.getMounts("11", 1, 10, "device,asc"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void 그룹_시계열은_interval_구간과_기본_step으로_조회하고_미수집이면_빈_시리즈다() {
        when(metrics.groupTrend(eq(FileSystemMetric.FS_USED_PERCENT), eq(NODE_SEL), any(), any(), eq("15m")))
                .thenReturn(List.of(new DataPoint(1L, 61.2)));

        FileSystemMetricTrendResponse trend = service.getGroupMetricTrend("11", FileSystemMetric.FS_USED_PERCENT, NodeMetricInterval.LAST_6H);

        assertThat(trend.unit()).isEqualTo("%");
        assertThat(trend.step()).isEqualTo("15m");
        assertThat(trend.to() - trend.from()).isEqualTo(6 * 3600);
        assertThat(trend.series()).hasSize(1);
        assertThat(trend.series().get(0).name()).isEqualTo("file_system");
        assertThat(trend.series().get(0).dataPoints()).containsExactly(new DataPoint(1L, 61.2));

        when(metrics.groupTrend(eq(FileSystemMetric.FS_TOTAL), eq(NODE_SEL), any(), any(), eq("1d"))).thenReturn(List.of());
        assertThat(service.getGroupMetricTrend("11", FileSystemMetric.FS_TOTAL, NodeMetricInterval.LAST_30D).series()).isEmpty();
    }

    // ── 개별 마운트 ─────────────────────────────────────────────

    @Test
    void 마운트_KPI는_용량_증가분_inode를_채우고_증가분_미수집은_null이다() {
        when(metrics.mount(NODE_SEL, "/")).thenReturn(Optional.of(ROOT));
        when(metrics.growth24hGb(NODE_SEL, "/")).thenReturn(-1.26);

        FileSystemMountSummaryResponse summary = service.getMountSummary("11", "/");

        assertThat(summary.totalGb()).isEqualTo(100.0);
        assertThat(summary.usedPercent()).isEqualTo(50.0);
        assertThat(summary.usedGb()).isEqualTo(50.0);
        assertThat(summary.freeGb()).isEqualTo(50.0);
        assertThat(summary.growth24hGb()).isEqualTo(-1.3);
        assertThat(summary.inodeUsagePercent()).isEqualTo(8.4);

        when(metrics.mount(NODE_SEL, "/data")).thenReturn(Optional.of(DATA));
        when(metrics.growth24hGb(NODE_SEL, "/data")).thenReturn(null);
        FileSystemMountSummaryResponse data = service.getMountSummary("11", "/data");
        assertThat(data.growth24hGb()).isNull();
        assertThat(data.inodeUsagePercent()).isNull();
    }

    @Test
    void 마운트_정보는_fstype_수집_상태_표본_시각을_내리고_표본_없는_마운트는_404다() {
        when(metrics.mount(NODE_SEL, "/boot")).thenReturn(Optional.of(BOOT));

        FileSystemMountInfoResponse info = service.getMountInfo("11", "/boot");

        assertThat(info.mountPath()).isEqualTo("/boot");
        assertThat(info.fsType()).isEqualTo("vfat");
        assertThat(info.collectStatus()).isEqualTo("COLLECTING");
        assertThat(info.lastCollectedAt()).isEqualTo(SAMPLE_AT.atZone(ZoneId.systemDefault()).toOffsetDateTime());

        when(metrics.mount(NODE_SEL, "/missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getMountInfo("11", "/missing"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getMountSummary("11", "/missing")).isInstanceOf(BusinessException.class);
        verify(metrics, never()).growth24hGb(eq(NODE_SEL), eq("/missing"));
    }

    @Test
    void 마운트_시계열은_경로를_그대로_넘기고_interval_구간으로_조회한다() {
        when(metrics.mountTrend(eq(FileSystemMetric.FS_FREE), eq(NODE_SEL), eq("/data"), any(), any(), eq("5m")))
                .thenReturn(List.of(new DataPoint(1L, 124.0)));

        FileSystemMetricTrendResponse trend = service.getMountMetricTrend("11", "/data", FileSystemMetric.FS_FREE, NodeMetricInterval.LAST_1H);

        assertThat(trend.metric()).isEqualTo(FileSystemMetric.FS_FREE);
        assertThat(trend.unit()).isEqualTo("GB");
        assertThat(trend.to() - trend.from()).isEqualTo(3600);
        assertThat(trend.series().get(0).dataPoints()).containsExactly(new DataPoint(1L, 124.0));
    }
}
