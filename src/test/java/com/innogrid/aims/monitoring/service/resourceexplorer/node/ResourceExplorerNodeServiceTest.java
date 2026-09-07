package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.repository.AssetRepository;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeDailyUsageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeDetailMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeMetricTrendResponse.Series;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeVmListItemResponse;
import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;
import com.innogrid.aims.monitoring.repository.MonitoringTargetRepository;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDetailMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDetailMetricsQueryService.DailyMax;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDetailMetricsQueryService.HostSummaryMetrics;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostMetricsQueryService.HostUtilization;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.Sebu1VirtualResourceProvider;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.Sebu1VirtualResourceProvider.VirtualResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 노드 상세 패널 테스트 — 스코프 접근 검증(404), 주요 지표 카드 null 규칙, 요약 차트 구간, 노드 정보,
 * 1세부 VM 목록, 일간 최대 사용 현황. 스코프·자산·Thanos·1세부 는 대역.
 *
 * 대역: node-01(assets.id 11, GPU_SERVER, mdc-01, node_exporter 10.0.0.1:9100 + dcgm 10.0.0.1:9400) — ADMIN 스코프
 *       node-09(assets.id 19) — 스코프 밖
 */
class ResourceExplorerNodeServiceTest {

    ResourceScopeService scopeService = mock(ResourceScopeService.class);
    AssetRepository assetRepository = mock(AssetRepository.class);
    MonitoringTargetRepository targetRepository = mock(MonitoringTargetRepository.class);
    Sebu1VirtualResourceProvider vmProvider = mock(Sebu1VirtualResourceProvider.class);
    HostDetailMetricsQueryService detailMetrics = mock(HostDetailMetricsQueryService.class);
    HostMetricsQueryService hostMetrics = mock(HostMetricsQueryService.class);

    ResourceExplorerNodeService service = new ResourceExplorerNodeService(
            scopeService, assetRepository, targetRepository, vmProvider, detailMetrics, hostMetrics);

    static final MetricSelector NODE_SEL = MetricSelector.instance("10.0.0.1:9100");
    static final MetricSelector DCGM_SEL = MetricSelector.instance("10.0.0.1:9400");

    Asset node01;
    HostSummaryMetrics fullMetrics = new HostSummaryMetrics(
            32.5, 40,
            264.0, 512.0,                  // memory used/total GB → 51.6%
            128.0, 1024.0,                 // file system used/total GB → 12.5%
            1_048_576.0, 524_288.0,
            88.5, 2,
            62.4, 80.0,                    // VRAM used/total GB → 78.0%
            Instant.now().getEpochSecond() - 10 * 86_400 - 60);   // 가동 10일

    @BeforeEach
    void setUp() {
        node01 = new Asset("mdc-01", Asset.AssetType.GPU_SERVER, "node-01");
        ReflectionTestUtils.setField(node01, "id", 11L);
        node01.setManufacturer("Supermicro");
        node01.setSystemType("Bare Metal");

        ScopedHost host = new ScopedHost("ADMIN", 11L, "node-01", "학습 노드", "mdc-01", ResourceStatus.RUNNING,
                "10.0.0.1:9100", List.of("10.0.0.1", "10.0.1.1"));
        when(scopeService.currentUserScope()).thenReturn(new Scope(Set.of("ADMIN"), List.of(host), List.of(), List.of()));
        when(assetRepository.findById(11L)).thenReturn(Optional.of(node01));

        MonitoringTarget dcgm = new MonitoringTarget("mdc-01", "node-01", "10.0.0.1", 9400, ExporterType.DCGM_EXPORTER);
        dcgm.setAsset(node01);
        MonitoringTarget nodeExporter = new MonitoringTarget("mdc-01", "node-01", "10.0.0.1", 9100, ExporterType.NODE_EXPORTER);
        nodeExporter.setAsset(node01);
        when(targetRepository.findByAsset_IdIn(anyCollection())).thenReturn(List.of(nodeExporter, dcgm));

        when(detailMetrics.summary(NODE_SEL, DCGM_SEL)).thenReturn(fullMetrics);
        when(vmProvider.findByHost(node01)).thenReturn(List.of());
    }

    // ── 접근 검증 ───────────────────────────────────────────────

    @Test
    void 토큰_스코프에_없는_노드나_잘못된_ID는_404다() {
        assertThatThrownBy(() -> service.getNodeSummary("19")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getNodeInfo("node-01")).isInstanceOf(BusinessException.class);   // assets.id 가 아님
        assertThatThrownBy(() -> service.getNodeVms("19", null, null, 1, 10, "name,asc")).isInstanceOf(BusinessException.class);
        verify(detailMetrics, never()).summary(any(), any());
    }

    // ── 주요 지표 카드 ──────────────────────────────────────────

    @Test
    void 주요_지표_카드는_사용률과_Free_Total_용량을_담는다() {
        NodeSummaryResponse summary = service.getNodeSummary("11");

        assertThat(summary.lastUpdatedAt()).isNotNull();
        assertThat(summary.cpu().utilizationPercent()).isEqualTo(32.5);
        assertThat(summary.cpu().coreCount()).isEqualTo(40);
        assertThat(summary.memory().utilizationPercent()).isEqualTo(51.6);
        assertThat(summary.memory().freeGb()).isEqualTo(248.0);
        assertThat(summary.memory().totalGb()).isEqualTo(512.0);
        assertThat(summary.fileSystem().utilizationPercent()).isEqualTo(12.5);
        assertThat(summary.networkTraffic().receiveBytesPerSec()).isEqualTo(1_048_576.0);
        assertThat(summary.gpu().utilizationPercent()).isEqualTo(88.5);
        assertThat(summary.gpu().gpuCount()).isEqualTo(2);
        assertThat(summary.vram().utilizationPercent()).isEqualTo(78.0);
        assertThat(summary.vram().freeGb()).isEqualTo(17.6);
    }

    @Test
    void 미수집_지표는_카드가_null_이다() {
        when(detailMetrics.summary(NODE_SEL, DCGM_SEL)).thenReturn(new HostSummaryMetrics(
                32.5, 40, null, null, 128.0, 1024.0, 1_048_576.0, null, null, null, null, null, null));
        NodeSummaryResponse summary = service.getNodeSummary("11");

        assertThat(summary.cpu()).isNotNull();
        assertThat(summary.memory()).isNull();                        // total 미수집
        assertThat(summary.fileSystem()).isNotNull();
        assertThat(summary.networkTraffic()).isNull();                // 송신 미수집
        assertThat(summary.gpu()).isNull();                           // GPU 미탑재
        assertThat(summary.vram()).isNull();

        when(detailMetrics.summary(NODE_SEL, DCGM_SEL)).thenReturn(HostSummaryMetrics.EMPTY);
        NodeSummaryResponse empty = service.getNodeSummary("11");
        assertThat(empty.cpu()).isNull();
        assertThat(empty.lastUpdatedAt()).isNotNull();
    }

    // ── 요약 차트 ───────────────────────────────────────────────

    @Test
    void 요약_차트는_기간_선택에_맞는_구간과_버킷으로_Thanos를_조회한다() {
        when(detailMetrics.trend(eq(NodeDetailMetric.CPU), eq(NODE_SEL), eq(DCGM_SEL), any(), any(), eq("15m")))
                .thenReturn(List.of(new Series("cpu", List.of(new DataPoint(1_757_000_000L, 30.0), new DataPoint(1_757_000_900L, 31.5)))));
        when(detailMetrics.trend(eq(NodeDetailMetric.NETWORK), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new Series("receive", List.of()), new Series("transmit", List.of())));

        List<NodeMetricTrendResponse> cpu = service.getNodeMetricTrend("11", NodeDetailMetric.CPU, NodeMetricInterval.LAST_6H);
        assertThat(cpu).hasSize(1);
        NodeMetricTrendResponse trend = cpu.get(0);
        assertThat(trend.metric()).isEqualTo(NodeDetailMetric.CPU);
        assertThat(trend.unit()).isEqualTo("%");
        assertThat(trend.step()).isEqualTo("15m");
        assertThat(trend.to() - trend.from()).isEqualTo(6 * 3600);
        assertThat(trend.series().get(0).dataPoints()).hasSize(2);

        // metric 미지정 → 6종 전부, NETWORK 는 2개 시리즈, 스텁 없는 지표는 빈 시리즈
        List<NodeMetricTrendResponse> all = service.getNodeMetricTrend("11", null, NodeMetricInterval.LAST_10M);
        assertThat(all).extracting(NodeMetricTrendResponse::metric).containsExactly(NodeDetailMetric.values());
        assertThat(all.get(3).series()).extracting(Series::name).containsExactly("receive", "transmit");
        assertThat(all.get(4).series()).isEmpty();
        assertThat(all.get(0).step()).isEqualTo("30s");
    }

    // ── 노드 정보 ───────────────────────────────────────────────

    @Test
    void 노드_정보는_자산_원장과_Thanos_현재값과_VM_수를_담는다() {
        when(vmProvider.findByHost(node01)).thenReturn(List.of(
                new VirtualResource("uuid-1", "web-01", "RUNNING", "10.0.0.101:9100"),
                new VirtualResource("uuid-2", "web-02", "SHUTOFF", null)));
        NodeInfoResponse info = service.getNodeInfo("11");

        assertThat(info.nodeId()).isEqualTo("11");
        assertThat(info.name()).isEqualTo("node-01");
        assertThat(info.ip()).isEqualTo("10.0.0.1,10.0.1.1");
        assertThat(info.uptimeDays()).isEqualTo(10);
        assertThat(info.type()).isEqualTo("GPU_SERVER");
        assertThat(info.vmCount()).isEqualTo(2);
        assertThat(info.cpuAllocatedCores()).isEqualTo(40);
        assertThat(info.memoryAllocatedGb()).isEqualTo(512.0);
        assertThat(info.storage()).isEqualTo("1024.0 GB");
        assertThat(info.provider()).isEqualTo("Supermicro");
        assertThat(info.platformType()).isEqualTo("Bare Metal");
    }

    // ── 가상머신 탭 ─────────────────────────────────────────────

    @Test
    void 가상머신_탭은_1세부_VM을_사용률과_함께_필터_정렬해_반환한다() {
        when(vmProvider.findByHost(node01)).thenReturn(List.of(
                new VirtualResource("uuid-1", "web-01", "RUNNING", "10.0.0.101:9100"),
                new VirtualResource("uuid-2", "web-02", "SHUTOFF", null),
                new VirtualResource("uuid-3", "db-01", "Running", "10.0.0.103:9100")));
        when(hostMetrics.utilizationByInstance(NodeMetricInterval.LAST_10M)).thenReturn(Map.of(
                "10.0.0.101:9100", new HostUtilization(45.2, 62.8, 38.6, 61.4, 100.0),
                "10.0.0.103:9100", new HostUtilization(88.9, 91.3, 76.2, 23.8, 100.0)));

        PageResponse<NodeVmListItemResponse> page = service.getNodeVms("11", null, null, 1, 10, "cpuUtilization,desc");
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.items()).extracting(NodeVmListItemResponse::name).containsExactly("db-01", "web-01", "web-02");

        NodeVmListItemResponse db = page.items().get(0);
        assertThat(db.vmId()).isEqualTo("SEBU1:uuid-3");
        assertThat(db.status()).isEqualTo(NodeStatus.RUNNING);       // 1세부 상태 문자열 매핑
        assertThat(db.diskUtilization()).isEqualTo(76.2);

        NodeVmListItemResponse off = page.items().get(2);
        assertThat(off.status()).isEqualTo(NodeStatus.ERROR);        // 알 수 없는 어휘는 ERROR 버킷
        assertThat(off.cpuUtilization()).isNull();                    // exporter 없음 → 미수집

        assertThat(service.getNodeVms("11", NodeStatus.RUNNING, "web", 1, 10, "name,asc").items())
                .extracting(NodeVmListItemResponse::name).containsExactly("web-01");
        assertThatThrownBy(() -> service.getNodeVms("11", null, null, 1, 10, "vmId,asc")).isInstanceOf(BusinessException.class);
    }

    @Test
    void 가상머신이_없거나_exporter가_없으면_Thanos를_조회하지_않는다() {
        assertThat(service.getNodeVms("11", null, null, 1, 10, "name,asc").items()).isEmpty();
        when(vmProvider.findByHost(node01)).thenReturn(List.of(new VirtualResource("uuid-2", "web-02", "SHUTOFF", null)));
        assertThat(service.getNodeVms("11", null, null, 1, 10, "name,asc").items()).hasSize(1);
        verify(hostMetrics, never()).utilizationByInstance(any());
    }

    // ── 자원 사용 현황 ──────────────────────────────────────────

    @Test
    void 자원_사용_현황은_일자별_일간_최대치를_반환하고_VM수는_오늘만_싣는다() {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        when(vmProvider.findByHost(node01)).thenReturn(List.of(new VirtualResource("uuid-1", "web-01", "RUNNING", null)));
        when(detailMetrics.dailyMax(eq(NODE_SEL), eq(today.minusDays(1)), eq(today))).thenReturn(List.of(
                new DailyMax(today.minusDays(1), 71.0, 60.2, null),
                new DailyMax(today, 80.3, 66.1, 13.0)));

        PageResponse<NodeDailyUsageResponse> page = service.getNodeUsage("11", NodeMetricInterval.LAST_24H, 1, 10, "date,desc");

        assertThat(page.items()).hasSize(2);
        NodeDailyUsageResponse todayRow = page.items().get(0);
        assertThat(todayRow.date()).isEqualTo(today);
        assertThat(todayRow.maxCpuUtilization()).isEqualTo(80.3);
        assertThat(todayRow.maxVmCount()).isEqualTo(1);
        NodeDailyUsageResponse yesterday = page.items().get(1);
        assertThat(yesterday.maxFileSystemUtilization()).isNull();     // 해당 일자 미수집
        assertThat(yesterday.maxVmCount()).isNull();                   // 이력 없음

        assertThat(service.getNodeUsage("11", NodeMetricInterval.LAST_24H, 1, 10, "maxFileSystemUtilization,desc").items())
                .extracting(NodeDailyUsageResponse::date).containsExactly(today, today.minusDays(1));   // null 은 마지막
        assertThatThrownBy(() -> service.getNodeUsage("11", NodeMetricInterval.LAST_24H, 1, 10, "vmCount,asc"))
                .isInstanceOf(BusinessException.class);
    }
}
