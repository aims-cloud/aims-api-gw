package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.cloudbarista.tumblebug.TumblebugClient;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeDetailMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeSummaryResponse.CapacityMetricCard;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeSummaryResponse.CpuCard;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeSummaryResponse.NetworkTrafficCard;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.VmInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.VmSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.VmSummaryResponse.GpuVramCard;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDetailMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDetailMetricsQueryService.HostSummaryMetrics;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 VM 상세 패널 조회 서비스 — Tumblebug Node(VM) 1개의 개요(주요 지표 카드·요약 차트)와 정보 표.
 *
 * <ul>
 *   <li>식별: nsId·infraId·nodeId 세 값. 트리 resourceId {@code TB:{ns}/{infra}/{node}} 를 쪼갠 것이다.</li>
 *   <li>접근: nsId 가 토큰 역할(roleName 지정 시 그 역할)의 role_namespaces 매핑에 있고, Tumblebug 스냅샷에 그 Node 가 있어야 한다. 아니면 404.</li>
 *   <li>지표: Thanos 를 {@code tb_ns/tb_infra/tb_node} 라벨로 좁혀 읽는다({@link MetricSelector#tumblebugNode}).
 *       VM 안 node_exporter·dcgm_exporter 에 같은 라벨이 붙으므로 선택자 하나로 두 exporter 를 가리킨다.</li>
 *   <li>정보: Tumblebug {@code GET /ns/{ns}/infra/{infra}/node/{node}} 원문에서 spec·image·IP·connection·cspResourceId 를 읽는다.</li>
 * </ul>
 * 모니터링 차트 지표는 노드 상세와 동일 6종이라 NodeDetailMetric·NodeMetricTrendResponse 를 재사용한다.
 */
@Service
public class ResourceExplorerVmService {

    private static final Logger log = LoggerFactory.getLogger(ResourceExplorerVmService.class);

    private final TumblebugPanelSupport support;
    private final TumblebugClient tumblebugClient;
    private final HostDetailMetricsQueryService detailMetrics;

    public ResourceExplorerVmService(TumblebugPanelSupport support,
                                     TumblebugClient tumblebugClient,
                                     HostDetailMetricsQueryService detailMetrics) {
        this.support = support;
        this.tumblebugClient = tumblebugClient;
        this.detailMetrics = detailMetrics;
    }

    /** 접근 검증을 통과한 VM — 스냅샷의 Node 요약과 Thanos 선택자 */
    record VmContext(String nsId, String infraId, TbNode node, MetricSelector selector) {
        String vmId() {
            return "TB:" + nsId + "/" + infraId + "/" + node.id();
        }
    }

    // ── 공개 API ────────────────────────────────────────────────

    /** 개요 탭 주요 지표 카드 5종 — 미수집이거나 GPU 미탑재면 해당 카드가 null 로 내려간다 */
    public VmSummaryResponse getVmSummary(String roleName, String nsId, String infraId, String nodeId) {
        VmContext ctx = accessibleVm(roleName, nsId, infraId, nodeId);
        HostSummaryMetrics m = detailMetrics.summary(ctx.selector(), ctx.selector());
        return new VmSummaryResponse(
                OffsetDateTime.now(ZoneId.systemDefault()),
                m.cpuPercent() == null ? null : new CpuCard(m.cpuPercent(), m.coreCount() == null ? 0 : m.coreCount()),
                capacityCard(m.memoryUsedGb(), m.memoryTotalGb()),
                capacityCard(m.fileSystemUsedGb(), m.fileSystemTotalGb()),
                m.receiveBytesPerSec() == null || m.transmitBytesPerSec() == null
                        ? null : new NetworkTrafficCard(m.receiveBytesPerSec(), m.transmitBytesPerSec()),
                gpuCard(m));
    }

    /** 요약 차트 — 지표별 시계열 (NETWORK 는 수신/송신 2개 시리즈, 미수집이면 빈 시리즈).
     *  metric 미지정 시 6종 전부. 조회 구간은 지금 기준 interval 기간, 버킷 간격은 기간별 defaultStep. */
    public List<NodeMetricTrendResponse> getVmMetricTrend(String roleName, String nsId, String infraId, String nodeId,
                                                          NodeDetailMetric metric, NodeMetricInterval interval) {
        VmContext ctx = accessibleVm(roleName, nsId, infraId, nodeId);
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();

        List<NodeDetailMetric> metrics = metric == null ? List.of(NodeDetailMetric.values()) : List.of(metric);
        return metrics.stream()
                .map(m -> new NodeMetricTrendResponse(m, m.unit(), from.getEpochSecond(), to.getEpochSecond(), step,
                        detailMetrics.trend(m, ctx.selector(), ctx.selector(), from, to, step)))
                .toList();
    }

    /** 개요 탭 VM 정보 표 — Tumblebug Node 상세 원문에서 구성 */
    public VmInfoResponse getVmInfo(String roleName, String nsId, String infraId, String nodeId) {
        VmContext ctx = accessibleVm(roleName, nsId, infraId, nodeId);
        Map<String, Object> node;
        try {
            node = tumblebugClient.getNode(nsId, infraId, nodeId);
        } catch (RuntimeException e) {
            log.warn("Tumblebug Node 상세 조회 실패 (ns={}, infra={}, node={}): {}", nsId, infraId, nodeId, e.getMessage());
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "Tumblebug 에서 VM 정보를 조회할 수 없습니다: " + nodeId);
        }
        if (node == null || str(node, "id") == null) {
            throw notFound(nodeId);
        }
        Map<String, Object> spec = nested(node, "spec");
        Map<String, Object> image = nested(node, "image");
        Map<String, Object> region = nested(node, "region");
        Map<String, Object> connection = nested(node, "connectionConfig");

        List<String> ips = new ArrayList<>();
        if (str(node, "publicIP") != null) {
            ips.add(str(node, "publicIP"));
        }
        if (str(node, "privateIP") != null) {
            ips.add(str(node, "privateIP"));
        }
        return new VmInfoResponse(
                ctx.vmId(),
                str(node, "name") == null ? nodeId : str(node, "name"),
                nsId,
                infraId,
                str(node, "nodeGroupId"),
                str(node, "status"),
                str(connection, "providerName"),
                str(node, "connectionName"),
                str(region, "region"),
                str(region, "zone"),
                str(node, "cspResourceId"),
                ips,
                str(node, "cspSpecName") == null ? str(spec, "cspSpecName") : str(node, "cspSpecName"),
                integer(spec, "vCPU"),
                decimal(spec, "memoryGiB"),
                integer(node, "rootDiskSize"),
                str(node, "rootDiskType"),
                str(image, "osType"),
                str(node, "createdTime"));
    }

    // ── 접근 검증 ───────────────────────────────────────────────

    /** nsId 가 토큰 역할(roleName 지정 시 그 역할) 스코프에 있고 Tumblebug 스냅샷에 Node 가 존재할 때만 컨텍스트를 만든다. 아니면 404. */
    private VmContext accessibleVm(String roleName, String nsId, String infraId, String nodeId) {
        TbNode node = support.accessibleNode(roleName, nsId, infraId, nodeId);
        return new VmContext(nsId, infraId, node, MetricSelector.tumblebugNode(nsId, infraId, nodeId));
    }

    private static BusinessException notFound(String nodeId) {
        return new BusinessException(HttpStatus.NOT_FOUND, "가상머신을 찾을 수 없습니다: " + nodeId);
    }

    // ── 카드 구성 ───────────────────────────────────────────────

    /** 사용률 + Free/Total 카드 — used/total 중 하나라도 미수집이면 카드 없음(null) */
    private static CapacityMetricCard capacityCard(Double usedGb, Double totalGb) {
        if (usedGb == null || totalGb == null) {
            return null;
        }
        double percent = totalGb <= 0 ? 0.0 : round1(usedGb / totalGb * 100);
        return new CapacityMetricCard(percent, round1(totalGb - usedGb), totalGb);
    }

    /** GPU 카드 — 사용률이 있어야 만들고, VRAM Free/Total 은 미수집이면 0 으로 둔다 */
    private static GpuVramCard gpuCard(HostSummaryMetrics m) {
        if (m.gpuPercent() == null) {
            return null;
        }
        double total = m.vramTotalGb() == null ? 0.0 : m.vramTotalGb();
        double used = m.vramUsedGb() == null ? 0.0 : m.vramUsedGb();
        return new GpuVramCard(m.gpuPercent(), round1(Math.max(0, total - used)), total);
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    // ── Tumblebug 응답 파싱 ─────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String str(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        if (value == null) {
            return null;
        }
        String s = value.toString();
        return s.isBlank() ? null : s;
    }

    private static Integer integer(Map<String, Object> source, String key) {
        Double d = decimal(source, key);
        return d == null ? null : (int) Math.round(d);
    }

    private static Double decimal(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
