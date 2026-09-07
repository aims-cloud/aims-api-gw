package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.cpu.VmCpuMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.memory.VmMemoryMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.network.VmNetworkMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 자원 1개의 항목(카테고리) 패널 지표를 Thanos 에서 읽는다 — CPU/Memory/Network 카테고리의 KPI·CPU 목록·시계열 전용.
 *
 * 자원은 {@link MetricSelector} 로 가리키므로 물리 호스트({@code instance})와 Tumblebug VM({@code tb_*}) 에 공통이다.
 * 전부 node_exporter 지표이며, Thanos 장애나 시계열 부재는 null/빈 목록(미수집)으로 돌려준다.
 * 상세 패널의 주요 지표·요약 차트는 {@link HostDetailMetricsQueryService} 가 담당한다.
 */
@Component
public class HostCategoryMetricsQueryService {

    private static final Logger log = LoggerFactory.getLogger(HostCategoryMetricsQueryService.class);
    private static final double GB = 1024.0 * 1024 * 1024;
    private static final String RATE_WINDOW = "5m";

    /** CPU 목록 1행 — node_cpu_info 의 package(소켓) 단위 집계 */
    public record CpuDevice(int socket, String vendor, String model, int coreCount, int threadCount) {}

    private final VictoriaMetricsClient client;

    public HostCategoryMetricsQueryService(VictoriaMetricsClient client) {
        this.client = client;
    }

    // ── PromQL ──────────────────────────────────────────────────

    /** mode 1개의 CPU 시간 비율(%) — 전체 CPU 평균 */
    static String cpuModePercent(MetricSelector s, String mode, String window) {
        return "avg by (instance) (rate(node_cpu_seconds_total" + s.braces("mode=\"" + mode + "\"") + "[" + window + "])) * 100";
    }

    static String cpuUsedPercent(MetricSelector s, String window) {
        return "100 - " + cpuModePercent(s, "idle", window);
    }

    static String cpuQuery(VmCpuMetric metric, MetricSelector s, String window) {
        return switch (metric) {
            case CPU_USED -> cpuUsedPercent(s, window);
            case CPU_IDLE -> cpuModePercent(s, "idle", window);
            case CPU_USER -> cpuModePercent(s, "user", window);
            case CPU_SYSTEM -> cpuModePercent(s, "system", window);
            case CPU_IO_WAIT -> cpuModePercent(s, "iowait", window);
            case CPU_NICE -> cpuModePercent(s, "nice", window);
            case CPU_IRQ -> cpuModePercent(s, "irq", window);
            case CPU_SOFT_IRQ -> cpuModePercent(s, "softirq", window);
            case CPU_STEAL -> cpuModePercent(s, "steal", window);
        };
    }

    static String memoryQuery(VmMemoryMetric metric, MetricSelector s) {
        String total = "node_memory_MemTotal_bytes" + s.braces();
        String avail = "node_memory_MemAvailable_bytes" + s.braces();
        return switch (metric) {
            case MEMORY_USED -> "(" + total + " - " + avail + ") / " + (long) GB;
            case MEMORY_USED_PERCENT -> "(1 - " + avail + " / " + total + ") * 100";
            case MEMORY_FREE -> "node_memory_MemFree_bytes" + s.braces() + " / " + (long) GB;
            case MEMORY_AVAILABLE -> avail + " / " + (long) GB;
            case MEMORY_AVAILABLE_PERCENT -> avail + " / " + total + " * 100";
            case MEMORY_TOTAL -> total + " / " + (long) GB;
            case MEMORY_CACHED -> "node_memory_Cached_bytes" + s.braces() + " / " + (long) GB;
            case MEMORY_BUFFERED -> "node_memory_Buffers_bytes" + s.braces() + " / " + (long) GB;
            case MEMORY_SHARED -> "node_memory_Shmem_bytes" + s.braces() + " / " + (long) GB;
        };
    }

    /** NIC 합산 초당 증가율 — lo·가상 인터페이스는 제외 */
    static String networkQuery(VmNetworkMetric metric, MetricSelector s, String window) {
        String counter = switch (metric) {
            case NETWORK_RECV -> "node_network_receive_bytes_total";
            case NETWORK_SENT -> "node_network_transmit_bytes_total";
            case NETWORK_PACKETS_RECV -> "node_network_receive_packets_total";
            case NETWORK_PACKETS_SENT -> "node_network_transmit_packets_total";
            case NETWORK_ERROR_IN -> "node_network_receive_errs_total";
            case NETWORK_ERROR_OUT -> "node_network_transmit_errs_total";
            case NETWORK_DROP_IN -> "node_network_receive_drop_total";
            case NETWORK_DROP_OUT -> "node_network_transmit_drop_total";
        };
        return "sum by (instance) (rate(" + counter + s.braces(HostDetailMetricsQueryService.NET_FILTER) + "[" + window + "]))";
    }

    static String cpuInfoQuery(MetricSelector s) {
        return "node_cpu_info" + s.braces();
    }

    static String cpuCountQuery(MetricSelector s) {
        return "count by (instance) (node_cpu_seconds_total" + s.braces("mode=\"idle\"") + ")";
    }

    // ── 조회 ────────────────────────────────────────────────────

    /** CPU 개요 KPI 카드 — Used / I/O Wait / System / Steal (%). 미수집은 null */
    public CpuSummaryResponse cpuSummary(MetricSelector s) {
        try {
            return new CpuSummaryResponse(
                    round1(scalar(cpuUsedPercent(s, RATE_WINDOW))),
                    round1(scalar(cpuModePercent(s, "iowait", RATE_WINDOW))),
                    round1(scalar(cpuModePercent(s, "system", RATE_WINDOW))),
                    round1(scalar(cpuModePercent(s, "steal", RATE_WINDOW))));
        } catch (RuntimeException e) {
            log.warn("Thanos CPU KPI 조회 실패 — 카드를 미수집으로 표시합니다: {}", e.getMessage());
            return new CpuSummaryResponse(null, null, null, null);
        }
    }

    /**
     * CPU 목록 — {@code node_cpu_info}(collector.cpu.info 활성 시) 의 package 별 벤더/모델/코어·스레드 수.
     * cpu.info 가 꺼져 있으면 논리 CPU 수만으로 소켓 0 한 행을 만든다(벤더·모델 null). 논리 CPU 도 없으면 빈 목록.
     */
    public List<CpuDevice> cpuDevices(MetricSelector s) {
        try {
            List<Map<String, Object>> info = client.queryResults(cpuInfoQuery(s));
            if (!info.isEmpty()) {
                return fromCpuInfo(info);
            }
            Double logical = scalar(cpuCountQuery(s));
            if (logical == null || logical <= 0) {
                return List.of();
            }
            int n = (int) Math.round(logical);
            return List.of(new CpuDevice(0, null, null, n, n));
        } catch (RuntimeException e) {
            log.warn("Thanos CPU 목록 조회 실패 — 빈 목록을 돌려줍니다: {}", e.getMessage());
            return List.of();
        }
    }

    /** CPU mode 별 시계열 — 미수집이면 빈 목록 */
    public List<DataPoint> cpuTrend(VmCpuMetric metric, MetricSelector s, Instant from, Instant to, String step) {
        return points(cpuQuery(metric, s, HostDetailMetricsQueryService.rateWindow(step)), from, to, step);
    }

    /** 메모리 지표 시계열(GB 또는 %) — 미수집이면 빈 목록 */
    public List<DataPoint> memoryTrend(VmMemoryMetric metric, MetricSelector s, Instant from, Instant to, String step) {
        return points(memoryQuery(metric, s), from, to, step);
    }

    /** 네트워크 지표 시계열(B/s, pps, /s) — 미수집이면 빈 목록 */
    public List<DataPoint> networkTrend(VmNetworkMetric metric, MetricSelector s, Instant from, Instant to, String step) {
        return points(networkQuery(metric, s, HostDetailMetricsQueryService.rateWindow(step)), from, to, step);
    }

    // ── 내부 ────────────────────────────────────────────────────

    /** node_cpu_info 벡터의 라벨(package/core/cpu/vendor/model_name)을 소켓 단위로 묶는다 */
    private static List<CpuDevice> fromCpuInfo(List<Map<String, Object>> results) {
        Map<Integer, String[]> meta = new HashMap<>();
        Map<Integer, Set<String>> cores = new HashMap<>();
        Map<Integer, Set<String>> threads = new HashMap<>();
        for (Map<String, Object> r : results) {
            Object m = r.get("metric");
            if (!(m instanceof Map<?, ?> labels)) {
                continue;
            }
            int socket = parseInt(labels.get("package"));
            meta.putIfAbsent(socket, new String[]{str(labels.get("vendor")), str(labels.get("model_name"))});
            cores.computeIfAbsent(socket, k -> new HashSet<>()).add(String.valueOf(labels.get("core")));
            threads.computeIfAbsent(socket, k -> new HashSet<>()).add(String.valueOf(labels.get("cpu")));
        }
        List<CpuDevice> devices = new ArrayList<>();
        meta.forEach((socket, vm) -> devices.add(new CpuDevice(socket, vm[0], vm[1],
                cores.get(socket).size(), threads.get(socket).size())));
        devices.sort(Comparator.comparingInt(CpuDevice::socket));
        return devices;
    }

    private List<DataPoint> points(String query, Instant from, Instant to, String step) {
        try {
            List<Map<String, Object>> results = client.queryRangeResults(query, from, to, step);
            if (results.isEmpty()) {
                return List.of();
            }
            List<DataPoint> points = new ArrayList<>();
            for (Object[] pair : HostDetailMetricsQueryService.values(results.get(0))) {
                points.add(new DataPoint(((Number) pair[0]).longValue(), round1((Double) pair[1])));
            }
            return points;
        } catch (RuntimeException e) {
            log.warn("Thanos 항목 차트 조회 실패 — 빈 시계열을 돌려줍니다: {}", e.getMessage());
            return List.of();
        }
    }

    private Double scalar(String query) {
        List<Map<String, Object>> results = client.queryResults(query);
        if (results.isEmpty()) {
            return null;
        }
        Object value = results.get(0).get("value");
        if (value instanceof List<?> pair && pair.size() >= 2) {
            return HostDetailMetricsQueryService.parse(pair.get(1));
        }
        return null;
    }

    private static Double round1(Double value) {
        return HostDetailMetricsQueryService.round1(value);
    }

    private static int parseInt(Object raw) {
        try {
            return raw == null ? 0 : Integer.parseInt(raw.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String str(Object raw) {
        return raw == null || raw.toString().isBlank() ? null : raw.toString();
    }
}
