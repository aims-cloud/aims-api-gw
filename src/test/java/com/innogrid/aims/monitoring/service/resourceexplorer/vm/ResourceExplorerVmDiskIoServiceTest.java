package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.RoleNamespace;
import com.innogrid.aims.infra.repository.RoleNamespaceRepository;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoDeviceListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio.DiskIoSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDiskIoMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDiskIoMetricsQueryService.DeviceIo;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VM Disk I/O 카테고리 패널 테스트 — 스코프·부재 404(Thanos 미조회), tb_* 선택자 전달, 표본 없음 미수집,
 * KPI 합산(지연 IOPS 가중·사용률 평균·값 없음), 장치 목록 정렬·페이징·400, 전체/장치 시계열.
 *
 * 대역: ns openstack / infra openstack-infra-01 / vm vm-1, ADMIN → openstack 매핑
 */
class ResourceExplorerVmDiskIoServiceTest {

    AuthService authService = mock(AuthService.class);
    RoleNamespaceRepository roleNamespaceRepository = mock(RoleNamespaceRepository.class);
    TumblebugTreeBuilder tumblebugTreeBuilder = mock(TumblebugTreeBuilder.class);
    HostDiskIoMetricsQueryService metrics = mock(HostDiskIoMetricsQueryService.class);
    TumblebugPanelSupport support = new TumblebugPanelSupport(authService, roleNamespaceRepository, tumblebugTreeBuilder);

    ResourceExplorerVmDiskIoService service = new ResourceExplorerVmDiskIoService(support, metrics);

    static final String NS = "openstack";
    static final String INFRA = "openstack-infra-01";
    static final String VM = "vm-1";
    static final MetricSelector SEL = MetricSelector.tumblebugNode(NS, INFRA, VM);

    static final DeviceIo VDA = new DeviceIo("vda", 1_000_000.0, 500_000.0, 100.0, 50.0, 0.4, 1.0, 20.0);
    static final DeviceIo NVME = new DeviceIo("nvme0n1", 3_000_000.0, 1_000_000.0, 300.0, 150.0, 0.2, 0.5, 60.0);
    static final DeviceIo IDLE = new DeviceIo("vdb", 0.0, 0.0, 0.0, 0.0, null, null, 0.0);   // 구간에 I/O 없음

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

        when(metrics.devices(SEL)).thenReturn(List.of(NVME, VDA, IDLE));
    }

    @Test
    void 스코프_밖_Namespace나_없는_VM은_404이고_Thanos를_조회하지_않는다() {
        assertThatThrownBy(() -> service.getDiskIoSummary(null, "other-ns", INFRA, VM))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getDiskIoDevices(null, NS, INFRA, "vm-999", 1, 10, "device,asc")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getDiskIoMetricTrend("GUEST", NS, INFRA, VM, DiskIoMetric.DISK_READ_BYTES, null, NodeMetricInterval.LAST_1H))
                .isInstanceOf(BusinessException.class);

        verify(metrics, never()).devices(any());
        verify(metrics, never()).trend(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 표본이_없는_VM은_장치_수_0에_값_전부_null_빈_목록이다() {
        when(metrics.devices(SEL)).thenReturn(List.of());

        assertThat(service.getDiskIoSummary("ADMIN", NS, INFRA, VM))
                .isEqualTo(new DiskIoSummaryResponse(0, null, null, null, null, null, null, null));
        assertThat(service.getDiskIoDevices(null, NS, INFRA, VM, 1, 10, "device,asc").totalElements()).isZero();
    }

    @Test
    void KPI는_바이트_IOPS를_합산하고_지연은_IOPS_가중_평균_사용률은_장치_평균이다() {
        DiskIoSummaryResponse kpi = service.getDiskIoSummary(null, NS, INFRA, VM);

        assertThat(kpi.deviceCount()).isEqualTo(3);
        assertThat(kpi.readBytesPerSec()).isEqualTo(4_000_000.0);
        assertThat(kpi.writeBytesPerSec()).isEqualTo(1_500_000.0);
        assertThat(kpi.readIops()).isEqualTo(400.0);
        assertThat(kpi.writeIops()).isEqualTo(200.0);
        assertThat(kpi.readLatencyMs()).isEqualTo(0.25);          // (0.4*100 + 0.2*300) / 400
        assertThat(kpi.writeLatencyMs()).isEqualTo(0.63);         // (1.0*50 + 0.5*150) / 200 = 0.625
        assertThat(kpi.utilizationPercent()).isEqualTo(26.67);    // (20 + 60 + 0) / 3

        when(metrics.devices(SEL)).thenReturn(List.of(IDLE));       // I/O 전혀 없음 → 지연 null
        DiskIoSummaryResponse idle = service.getDiskIoSummary(null, NS, INFRA, VM);
        assertThat(idle.deviceCount()).isEqualTo(1);
        assertThat(idle.readLatencyMs()).isNull();
        assertThat(idle.readBytesPerSec()).isZero();
    }

    @Test
    void 장치_목록은_행을_소수2자리로_채우고_정렬_페이징하며_미지원_필드는_400이다() {
        PageResponse<DiskIoDeviceListItemResponse> page = service.getDiskIoDevices(null, NS, INFRA, VM, 1, 10, "device,asc");
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.items()).extracting(DiskIoDeviceListItemResponse::device).containsExactly("nvme0n1", "vda", "vdb");
        assertThat(page.items().get(1).readLatencyMs()).isEqualTo(0.4);
        assertThat(page.items().get(2).readLatencyMs()).isNull();

        assertThat(service.getDiskIoDevices(null, NS, INFRA, VM, 1, 10, "readBytesPerSec,desc").items())
                .extracting(DiskIoDeviceListItemResponse::device).containsExactly("nvme0n1", "vda", "vdb");
        assertThat(service.getDiskIoDevices(null, NS, INFRA, VM, 1, 10, "readLatencyMs,desc").items())
                .extracting(DiskIoDeviceListItemResponse::device).containsExactly("vda", "nvme0n1", "vdb");   // null 은 마지막

        PageResponse<DiskIoDeviceListItemResponse> second = service.getDiskIoDevices(null, NS, INFRA, VM, 2, 2, "device,asc");
        assertThat(second.items()).extracting(DiskIoDeviceListItemResponse::device).containsExactly("vdb");
        assertThat(second.totalPages()).isEqualTo(2);

        assertThatThrownBy(() -> service.getDiskIoDevices(null, NS, INFRA, VM, 1, 10, "queueDepth,asc"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void 시계열은_전체_합산이면_disk_io_시리즈_장치_지정이면_장치명_시리즈로_interval_구간을_조회한다() {
        when(metrics.trend(eq(DiskIoMetric.DISK_UTILIZATION), eq(SEL), isNull(), any(), any(), eq("15m")))
                .thenReturn(List.of(new DataPoint(1L, 40.0)));
        when(metrics.trend(eq(DiskIoMetric.DISK_READ_IOPS), eq(SEL), eq("vda"), any(), any(), eq("30s")))
                .thenReturn(List.of(new DataPoint(1L, 100.0)));

        DiskIoMetricTrendResponse all = service.getDiskIoMetricTrend(null, NS, INFRA, VM, DiskIoMetric.DISK_UTILIZATION, "  ", NodeMetricInterval.LAST_6H);
        assertThat(all.device()).isNull();
        assertThat(all.unit()).isEqualTo("%");
        assertThat(all.step()).isEqualTo("15m");
        assertThat(all.to() - all.from()).isEqualTo(6 * 3600L);
        assertThat(all.series()).hasSize(1);
        assertThat(all.series().get(0).name()).isEqualTo("disk_io");

        DiskIoMetricTrendResponse one = service.getDiskIoMetricTrend(null, NS, INFRA, VM, DiskIoMetric.DISK_READ_IOPS, "vda", NodeMetricInterval.LAST_10M);
        assertThat(one.device()).isEqualTo("vda");
        assertThat(one.series().get(0).name()).isEqualTo("vda");
        assertThat(one.series().get(0).dataPoints()).containsExactly(new DataPoint(1L, 100.0));

        assertThat(service.getDiskIoMetricTrend(null, NS, INFRA, VM, DiskIoMetric.DISK_WRITE_BYTES, "vdz", NodeMetricInterval.LAST_10M).series()).isEmpty();
    }
}
