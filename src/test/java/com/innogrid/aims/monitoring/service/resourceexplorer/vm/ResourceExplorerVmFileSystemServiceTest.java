package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.RoleNamespace;
import com.innogrid.aims.infra.repository.RoleNamespaceRepository;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMountInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMountListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem.FileSystemMountSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostFileSystemMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostFileSystemMetricsQueryService.MountUsage;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbInfra;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbNode;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VM File system 카테고리 패널 테스트 — 스코프·부재 404(Thanos 미조회), tb_* 선택자 전달, 표본 없음 미수집,
 * 그룹 KPI 집계(Near Full 90% 경계·최대 마운트·inode null), 목록 정렬·페이징·400, 마운트 KPI·정보·404, interval 시계열.
 *
 * 대역: ns openstack / infra openstack-infra-01 / vm vm-1, ADMIN → openstack 매핑
 *       마운트: / ext4 100GB 중 50GB 사용(inode 8.4%), /data xfs 1TB 중 900GB 사용(inode 미보고), /boot vfat 1GB 중 0.3GB 사용(inode 1%)
 */
class ResourceExplorerVmFileSystemServiceTest {

    AuthService authService = mock(AuthService.class);
    RoleNamespaceRepository roleNamespaceRepository = mock(RoleNamespaceRepository.class);
    TumblebugTreeBuilder tumblebugTreeBuilder = mock(TumblebugTreeBuilder.class);
    HostFileSystemMetricsQueryService metrics = mock(HostFileSystemMetricsQueryService.class);
    TumblebugPanelSupport support = new TumblebugPanelSupport(authService, roleNamespaceRepository, tumblebugTreeBuilder);

    ResourceExplorerVmFileSystemService service = new ResourceExplorerVmFileSystemService(support, metrics);

    static final String NS = "openstack";
    static final String INFRA = "openstack-infra-01";
    static final String VM = "vm-1";
    static final MetricSelector SEL = MetricSelector.tumblebugNode(NS, INFRA, VM);
    static final double GB = 1024.0 * 1024 * 1024;
    static final Instant SAMPLE_AT = Instant.ofEpochSecond(1_700_000_000L);

    static final MountUsage ROOT = new MountUsage("/", "ext4", "/dev/vda1", 100 * GB, 50 * GB, 6_553_600.0, 6_000_000.0, SAMPLE_AT);
    static final MountUsage DATA = new MountUsage("/data", "xfs", "/dev/vdb1", 1024 * GB, 124 * GB, 0.0, null, SAMPLE_AT);
    static final MountUsage BOOT = new MountUsage("/boot", "vfat", "/dev/vda2", 1 * GB, 0.7 * GB, 1000.0, 990.0, SAMPLE_AT);

    @BeforeEach
    void setUp() {
        when(authService.currentUserRoleIds()).thenReturn(new LinkedHashSet<>(List.of("ADMIN")));
        Role admin = new Role("ADMIN", "관리자");
        when(roleNamespaceRepository.findByRole_NameIn(anyCollection())).thenAnswer(inv -> {
            Collection<String> roles = inv.getArgument(0);
            return roles.contains("ADMIN") ? List.of(new RoleNamespace(admin, NS)) : List.of();
        });
        when(tumblebugTreeBuilder.snapshot(anyCollection())).thenReturn(new TbSnapshot(Instant.now(),
                Map.of(NS, NS),
                Map.of(NS, List.of(new TbInfra(INFRA, INFRA, "Running:1 (R:1/1)", List.of(
                        new TbNode(VM, VM, "Running", "g1", "openstack", null, "172.16.0.1"))))),
                Set.of(NS), Set.of(), false));

        when(metrics.mounts(SEL)).thenReturn(List.of(ROOT, DATA, BOOT));
        when(metrics.mount(eq(SEL), anyString())).thenReturn(Optional.empty());
        when(metrics.mount(SEL, "/")).thenReturn(Optional.of(ROOT));
        when(metrics.mount(SEL, "/data")).thenReturn(Optional.of(DATA));
    }

    // ── 스코프 ──────────────────────────────────────────────────

    @Test
    void 스코프_밖_Namespace나_없는_VM은_404이고_Thanos를_조회하지_않는다() {
        assertThatThrownBy(() -> service.getGroupSummary(null, "other-ns", INFRA, VM))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getMounts(null, NS, INFRA, "vm-999", 1, 10, "mountPath,asc")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getMountSummary("GUEST", NS, INFRA, VM, "/")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getMountMetricTrend(null, NS, "infra-x", VM, "/", FileSystemMetric.FS_USED, NodeMetricInterval.LAST_1H))
                .isInstanceOf(BusinessException.class);

        verify(metrics, never()).mounts(any());
        verify(metrics, never()).mount(any(), any());
        verify(metrics, never()).groupTrend(any(), any(), any(), any(), any());
        verify(metrics, never()).mountTrend(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 표본이_없는_VM은_마운트_0_합계_0_빈_목록으로_내린다() {
        when(metrics.mounts(SEL)).thenReturn(List.of());

        FileSystemGroupSummaryResponse kpi = service.getGroupSummary("ADMIN", NS, INFRA, VM);
        assertThat(kpi.totalMountCount()).isZero();
        assertThat(kpi.nearFullCount()).isZero();
        assertThat(kpi.maxUsedPercentMountPath()).isNull();
        assertThat(kpi.totalSizeGb()).isZero();
        assertThat(kpi.maxInodeUsagePercent()).isNull();
        assertThat(service.getMounts(null, NS, INFRA, VM, 1, 10, "mountPath,asc").totalElements()).isZero();
    }

    // ── 그룹 ────────────────────────────────────────────────────

    @Test
    void 그룹_KPI는_Near_Full_90퍼센트_경계와_최대_사용_마운트_경로_합계_최대_inode를_집계한다() {
        FileSystemGroupSummaryResponse kpi = service.getGroupSummary(null, NS, INFRA, VM);

        assertThat(kpi.totalMountCount()).isEqualTo(3);
        assertThat(kpi.nearFullCount()).isZero();                        // 최대 87.9%
        assertThat(kpi.maxUsedPercent()).isEqualTo(87.9);
        assertThat(kpi.maxUsedPercentMountPath()).isEqualTo("/data");
        assertThat(kpi.maxUsedGb()).isEqualTo(900.0);
        assertThat(kpi.maxUsedGbMountPath()).isEqualTo("/data");
        assertThat(kpi.totalSizeGb()).isEqualTo(1125.0);
        assertThat(kpi.totalUsedGb()).isEqualTo(950.3);
        assertThat(kpi.totalFreeGb()).isEqualTo(174.7);
        assertThat(kpi.maxInodeUsagePercent()).isEqualTo(8.4);

        when(metrics.mounts(SEL)).thenReturn(List.of(ROOT,
                new MountUsage("/edge", "ext4", "/dev/vdc1", 10 * GB, 1 * GB, null, null, SAMPLE_AT)));   // 정확히 90%
        assertThat(service.getGroupSummary(null, NS, INFRA, VM).nearFullCount()).isEqualTo(1);

        when(metrics.mounts(SEL)).thenReturn(List.of(DATA));                                            // inode 미보고만
        assertThat(service.getGroupSummary(null, NS, INFRA, VM).maxInodeUsagePercent()).isNull();
    }

    @Test
    void 마운트_목록은_행을_GB_소수1자리로_채우고_정렬_페이징하며_미지원_필드는_400이다() {
        PageResponse<FileSystemMountListItemResponse> page = service.getMounts(null, NS, INFRA, VM, 1, 10, "mountPath,asc");
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.items()).extracting(FileSystemMountListItemResponse::mountPath).containsExactly("/", "/boot", "/data");
        FileSystemMountListItemResponse boot = page.items().get(1);
        assertThat(boot.fsType()).isEqualTo("vfat");
        assertThat(boot.totalGb()).isEqualTo(1.0);
        assertThat(boot.usedPercent()).isEqualTo(30.0);
        assertThat(boot.usedGb()).isEqualTo(0.3);
        assertThat(boot.freeGb()).isEqualTo(0.7);
        assertThat(boot.inodeUsagePercent()).isEqualTo(1.0);
        assertThat(page.items().get(2).inodeUsagePercent()).isNull();

        assertThat(service.getMounts(null, NS, INFRA, VM, 1, 10, "usedPercent,desc").items())
                .extracting(FileSystemMountListItemResponse::mountPath).containsExactly("/data", "/", "/boot");
        assertThat(service.getMounts(null, NS, INFRA, VM, 1, 10, "inodeUsagePercent,desc").items())
                .extracting(FileSystemMountListItemResponse::mountPath).containsExactly("/", "/boot", "/data");   // null 은 마지막

        PageResponse<FileSystemMountListItemResponse> second = service.getMounts(null, NS, INFRA, VM, 2, 2, "mountPath,asc");
        assertThat(second.items()).extracting(FileSystemMountListItemResponse::mountPath).containsExactly("/data");
        assertThat(second.totalPages()).isEqualTo(2);

        assertThatThrownBy(() -> service.getMounts(null, NS, INFRA, VM, 1, 10, "device,asc"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void 그룹_시계열은_interval_구간과_기본_step으로_조회하고_미수집이면_빈_시리즈다() {
        when(metrics.groupTrend(eq(FileSystemMetric.FS_USED_PERCENT), eq(SEL), any(), any(), eq("15m")))
                .thenReturn(List.of(new DataPoint(1L, 48.2)));

        FileSystemMetricTrendResponse trend = service.getGroupMetricTrend(null, NS, INFRA, VM,
                FileSystemMetric.FS_USED_PERCENT, NodeMetricInterval.LAST_6H);

        assertThat(trend.metric()).isEqualTo(FileSystemMetric.FS_USED_PERCENT);
        assertThat(trend.unit()).isEqualTo("%");
        assertThat(trend.step()).isEqualTo("15m");
        assertThat(trend.to() - trend.from()).isEqualTo(6 * 3600L);
        assertThat(trend.series()).hasSize(1);
        assertThat(trend.series().get(0).name()).isEqualTo("file_system");
        assertThat(trend.series().get(0).dataPoints()).containsExactly(new DataPoint(1L, 48.2));

        when(metrics.groupTrend(eq(FileSystemMetric.FS_TOTAL), eq(SEL), any(), any(), eq("30s"))).thenReturn(List.of());
        assertThat(service.getGroupMetricTrend(null, NS, INFRA, VM, FileSystemMetric.FS_TOTAL, NodeMetricInterval.LAST_10M).series()).isEmpty();
    }

    // ── 개별 마운트 ─────────────────────────────────────────────

    @Test
    void 마운트_KPI는_용량_증가분_inode를_채우고_증가분_미수집은_null이며_표본_없는_마운트는_404다() {
        when(metrics.growth24hGb(SEL, "/")).thenReturn(2.34);
        when(metrics.growth24hGb(SEL, "/data")).thenReturn(null);     // 24h 전 표본 없음

        FileSystemMountSummaryResponse root = service.getMountSummary(null, NS, INFRA, VM, "/");
        assertThat(root.totalGb()).isEqualTo(100.0);
        assertThat(root.usedPercent()).isEqualTo(50.0);
        assertThat(root.usedGb()).isEqualTo(50.0);
        assertThat(root.freeGb()).isEqualTo(50.0);
        assertThat(root.growth24hGb()).isEqualTo(2.3);
        assertThat(root.inodeUsagePercent()).isEqualTo(8.4);

        FileSystemMountSummaryResponse data = service.getMountSummary(null, NS, INFRA, VM, "/data");
        assertThat(data.growth24hGb()).isNull();
        assertThat(data.inodeUsagePercent()).isNull();

        assertThatThrownBy(() -> service.getMountSummary(null, NS, INFRA, VM, "/mnt/none"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void 마운트_정보는_fstype_수집_상태_표본_시각을_내리고_표본_없는_마운트는_404다() {
        FileSystemMountInfoResponse info = service.getMountInfo("ADMIN", NS, INFRA, VM, "/");

        assertThat(info.mountPath()).isEqualTo("/");
        assertThat(info.fsType()).isEqualTo("ext4");
        assertThat(info.collectStatus()).isEqualTo(MonitoringStatus.COLLECTING);
        assertThat(info.lastCollectedAt()).isEqualTo(OffsetDateTime.ofInstant(SAMPLE_AT, ZoneId.systemDefault()));

        assertThatThrownBy(() -> service.getMountInfo(null, NS, INFRA, VM, "/boot"))       // 목록엔 있어도 단건 표본이 없으면 404
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void 마운트_시계열은_경로와_tb_선택자를_그대로_넘기고_interval_구간으로_조회한다() {
        when(metrics.mountTrend(eq(FileSystemMetric.FS_FREE), eq(SEL), eq("/data"), any(), any(), eq("1h")))
                .thenReturn(List.of(new DataPoint(1L, 124.0), new DataPoint(2L, 123.5)));

        FileSystemMetricTrendResponse trend = service.getMountMetricTrend(null, NS, INFRA, VM, "/data",
                FileSystemMetric.FS_FREE, NodeMetricInterval.LAST_24H);

        assertThat(trend.unit()).isEqualTo("GB");
        assertThat(trend.step()).isEqualTo("1h");
        assertThat(trend.to() - trend.from()).isEqualTo(24 * 3600L);
        assertThat(trend.series().get(0).dataPoints()).hasSize(2);
    }
}
