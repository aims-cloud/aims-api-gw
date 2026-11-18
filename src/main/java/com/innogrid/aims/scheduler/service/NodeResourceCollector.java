package com.innogrid.aims.scheduler.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.scheduler.metrics.SchedulerMetricsGateway;
import com.innogrid.aims.scheduler.model.ExporterInstances;
import com.innogrid.aims.scheduler.model.GpuTelemetry;
import com.innogrid.aims.scheduler.model.NodePowerSpecs;
import com.innogrid.aims.scheduler.repository.NodePowerSpecRepository;
import com.innogrid.aims.scheduler.metrics.VictoriaMetricsGateway;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.model.ExcludedNode;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.NodeResource.PowerSource;

/**
 * Thanos/Prometheus에서 실시간 노드 자원 상태를 수집한다.
 * 스케줄링 엔진에 NodeResource 목록을 제공한다.
 *
 * <p><b>핵심 메트릭(CPU 사용률·코어 수·MemTotal·MemAvailable)을 알 수 없는 노드는 수집하지
 * 않는다.</b> 질의가 실패하거나 시리즈가 없을 때 0으로 채우면 그 노드는 "CPU 0%의 빈 노드"로
 * 보인다 — 점수가 치솟아 배치가 몰리고, 통합 진단은 가동 중인 노드를 반납 후보로 판정한다.
 * 코어 수가 0이면 {@code Math.max(…, 1)}로 1코어짜리 가짜 노드가 만들어지기도 했다.
 * 모르는 노드는 후보가 아니다. 제외 사실은 WARN 로그로 남긴다.</p>
 *
 * <p>디스크·네트워크·전력·온도는 없으면 0으로 둔다. 전력·온도는 {@link PowerSource} 플래그가
 * "수집 안 됨"을 표시하고, 디스크 총량 0은 "모름"이라 C1 디스크 검사가 그 노드를 건너뛴다.</p>
 */
@Service
public class NodeResourceCollector {

    private static final Logger log = LoggerFactory.getLogger(NodeResourceCollector.class);

    /** 메트릭 질의 자리 — 실제 저장소 또는 가상 클러스터(모의 메트릭 모드). 이 클래스는 어느 쪽인지 모른다. */
    private final SchedulerMetricsGateway vmClient;
    private final SchedulerProperties properties;
    private final PowerModel powerModel;
    private final MetricsQueryGuard guard;

    /** 노드 전력 사양 원장. null이면(원장 없이 조립한 테스트 경로) 정격은 설정값에서 온다. */
    private final NodePowerSpecRepository powerSpecRepository;

    /** 익스포터 instance 해석. 원장 없이 조립하면 호스트명 일치와 그대로만 쓴다. */
    private final ExporterInstanceResolver exporterResolver;

    @Autowired
    public NodeResourceCollector(SchedulerMetricsGateway metrics,
                                 SchedulerProperties properties,
                                 PowerModel powerModel,
                                 MetricsQueryGuard guard,
                                 NodePowerSpecRepository powerSpecRepository,
                                 ExporterInstanceResolver exporterResolver) {
        this.vmClient = metrics;
        this.properties = properties;
        this.powerModel = powerModel;
        this.guard = guard;
        this.powerSpecRepository = powerSpecRepository;
        this.exporterResolver = exporterResolver != null ? exporterResolver
                : new ExporterInstanceResolver(metrics, guard, properties);
    }

    /** 익스포터 대응 원장 없이 조립하는 형태(테스트 경로) — 호스트명 일치와 그대로만 쓴다. */
    public NodeResourceCollector(SchedulerMetricsGateway metrics,
                                 SchedulerProperties properties,
                                 PowerModel powerModel,
                                 MetricsQueryGuard guard,
                                 NodePowerSpecRepository powerSpecRepository) {
        this(metrics, properties, powerModel, guard, powerSpecRepository, null);
    }

    /** 원장 없이 조립하는 형태 — 정격은 설정값. */
    public NodeResourceCollector(SchedulerMetricsGateway metrics,
                                 SchedulerProperties properties,
                                 PowerModel powerModel,
                                 MetricsQueryGuard guard) {
        this(metrics, properties, powerModel, guard, null);
    }

    /** 클라이언트를 직접 받는 형태(테스트·조립 경로) — 기본 게이트웨이로 감싼다. */
    public NodeResourceCollector(VictoriaMetricsClient vmClient,
                                 SchedulerProperties properties,
                                 PowerModel powerModel,
                                 MetricsQueryGuard guard) {
        this(new VictoriaMetricsGateway(vmClient), properties, powerModel, guard);
    }

    /** 스프링 없이 조립하는 형태(단위 테스트). 보호 장치는 설정의 풀 크기로 직접 만든다. */
    public NodeResourceCollector(VictoriaMetricsClient vmClient,
                                 SchedulerProperties properties,
                                 PowerModel powerModel) {
        this(vmClient, properties, powerModel, new MetricsQueryGuard(properties.getMetrics().getParallelism()));
    }

    /** 노드 한 대 수집의 결과 — 후보 노드 또는 제외 사유 중 하나. 작업 스레드는 공유 목록을 건드리지 않고 이것만 돌려준다. */
    private record NodeOutcome(NodeResource node, ExcludedNode excluded) {
        static NodeOutcome of(NodeResource node) { return new NodeOutcome(node, null); }
        static NodeOutcome excluded(ExcludedNode excluded) { return new NodeOutcome(null, excluded); }
    }

    /**
     * 수집 결과 — 후보 노드와, 핵심 메트릭을 알 수 없어 뺀 노드.
     *
     * <p>뺀 노드를 함께 돌려주는 이유는 진단 때문이다. 로그에만 남기면 클러스터 진단의 노드 수가
     * 조용히 줄어들어 "10대 중 1대 제외"가 "9대 클러스터"로 보인다.</p>
     */
    public record Snapshot(List<NodeResource> nodes, List<ExcludedNode> excluded) {
        public Snapshot {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            excluded = excluded == null ? List.of() : List.copyOf(excluded);
        }
    }

    /**
     * 모든 활성 노드의 실시간 자원 상태를 수집한다.
     * 핵심 메트릭을 알 수 없는 노드는 목록에 들어가지 않는다 — 제외 목록은 {@link #collect()}가 준다.
     */
    public List<NodeResource> collectAll() {
        return collect().nodes();
    }

    /** 원장 스냅샷 — 수집 1회당 한 번만 읽는다. 원장이 없거나 비면 {@link NodePowerSpecs#EMPTY}. */
    private NodePowerSpecs powerSpecs() {
        return powerSpecRepository == null ? NodePowerSpecs.EMPTY : NodePowerSpecs.of(powerSpecRepository.findAll());
    }

    /**
     * 후보 노드와 제외 노드를 함께 수집한다.
     *
     * <p><b>질의는 상한 안에서만 기다린다</b>({@code MetricsQueryGuard}). 대상 목록·GPU 개수·GPU 축 셋·IPMI 발견은 단건 상한, 노드 한 대의 질의 10회는
     * 노드 상한, 스냅샷 전체는 전체 상한이다. 노드별 수집은 보호 장치의 풀에서 병렬로 돌리되 결과는 <b>제출 순서대로</b>
     * 모아 노드 순서가 순차 수집과 같다. 상한을 넘긴 노드는 "응답 없음"으로 제외 목록에 넣고 나머지로 계속 간다 —
     * 저장소가 멈춰도 요청 스레드는 멈추지 않는다. 대상 목록 질의가 상한을 넘기면 빈 스냅샷(예외로 끝났을 때의 "수집 실패"와
     * 같은 결과)이다. 작업 스레드는 공유 목록을 건드리지 않고 {@link NodeOutcome}만 돌려준다 — 뒤늦게 끝난 작업이
     * 조립이 끝난 목록을 오염시키지 않게 하기 위해서다.</p>
     */
    @SuppressWarnings("unchecked")
    public Snapshot collect() {
        List<NodeResource> nodes = new ArrayList<>();
        List<ExcludedNode> excluded = new ArrayList<>();
        SchedulerProperties.Metrics limits = properties.getMetrics();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.getCollectTimeoutMs());

        try {
            // up == 1인 노드만 수집
            Map<String, Object> upResponse = guard.call(() -> vmClient.query(
                    String.format(ExporterInstanceResolver.NODE_DISCOVERY, properties.getNodeJob())), limits.getQueryTimeoutMs());
            if (upResponse == null) return new Snapshot(nodes, excluded);

            Map<String, Object> data = (Map<String, Object>) upResponse.get("data");
            if (data == null) return new Snapshot(nodes, excluded);

            List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");
            if (results == null) return new Snapshot(nodes, excluded);

            // GPU 개수는 노드마다 조회하지 않고 한 번에 받아 둔다. 키는 DCGM 시리즈의 instance다.
            Map<String, Integer> gpuCounts = exporterResolver.discoverGpuCounts();
            // GPU 축(사용률·온도·전력)도 클러스터 한 번씩 — GPU 시리즈가 아예 없으면 세 질의를 던지지 않는다.
            ExporterInstanceResolver.GpuTelemetryDiscovery gpuDiscovery = gpuCounts.isEmpty()
                    ? ExporterInstanceResolver.GpuTelemetryDiscovery.NONE : exporterResolver.discoverGpuTelemetry();
            Map<String, GpuTelemetry> gpuTelemetry = gpuDiscovery.byInstance();
            warnGpuTelemetryGaps(gpuCounts, gpuDiscovery);
            // 세 익스포터의 instance를 잇는 해석 — 원장 → 호스트명 일치 → 그대로. 실장비는 익스포터마다 instance가
            // 달라(노드 :9100, DCGM :9400, IPMI는 중앙 주소) 노드 instance로는 GPU·전력·온도를 찾을 수 없었다.
            ExporterInstances exporters = exporterResolver.resolve(gpuCounts.keySet(), exporterResolver.discoverIpmiInstances());
            // 정격 전력 원장도 수집 1회당 한 번만 읽는다. 비어 있으면 정격은 설정값에서 온다.
            NodePowerSpecs specs = powerSpecs();

            List<String> instances = new ArrayList<>();
            List<String> mdcIds = new ArrayList<>();
            for (Map<String, Object> result : results) {
                Map<String, String> labels = (Map<String, String>) result.get("metric");
                instances.add(labels.getOrDefault("instance", "unknown"));
                mdcIds.add(labels.getOrDefault("mdc_id", "unknown"));
            }
            // 노드 전체를 한 번에 해석한다 — 같은 시리즈를 가리킨 노드가 둘이면 둘 다 미계측(한 값이 두 번 세어지면 합계가 부푼다)
            Map<String, String> dcgmOfNode = exporters.dcgmFor(instances);
            Map<String, String> ipmiOfNode = exporters.ipmiFor(instances);

            List<Future<NodeOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < instances.size(); i++) {
                String instance = instances.get(i);
                String mdcId = mdcIds.get(i);
                String dcgmInstance = dcgmOfNode.getOrDefault(instance, instance);
                String ipmiInstance = ipmiOfNode.getOrDefault(instance, instance);
                if (!dcgmInstance.equals(exporters.dcgmOf(instance)) || !ipmiInstance.equals(exporters.ipmiOf(instance))) {
                    log.warn("익스포터 대응 충돌: instance={} — 다른 노드와 같은 시리즈(DCGM {} / IPMI {})를 가리켜 이 노드는 그대로 둔다. "
                            + "원장에서 한 instance 를 한 노드에만 적으십시오", instance, exporters.dcgmOf(instance), exporters.ipmiOf(instance));
                }
                int gpu = gpuCounts.getOrDefault(dcgmInstance, 0);
                GpuTelemetry telemetry = gpu > 0 ? gpuTelemetry.getOrDefault(dcgmInstance, GpuTelemetry.EMPTY) : GpuTelemetry.EMPTY;
                futures.add(guard.submit(() -> collectNodeMetrics(mdcId, instance, ipmiInstance, gpu, telemetry, specs)));
            }

            List<Optional<NodeOutcome>> outcomes = guard.awaitAll(futures, limits.getNodeTimeoutMs(), deadline);
            for (int i = 0; i < outcomes.size(); i++) {
                Optional<NodeOutcome> outcome = outcomes.get(i);
                if (outcome.isEmpty()) {
                    log.warn("노드 수집 제외: instance={} — 메트릭 질의가 상한 {}ms 안에 끝나지 않았다(저장소 응답 없음). "
                            + "기다리기를 멈추고 후보에서 뺀다", instances.get(i), limits.getNodeTimeoutMs());
                    excluded.add(ExcludedNode.timedOut(mdcIds.get(i), instances.get(i), limits.getNodeTimeoutMs()));
                } else if (outcome.get().node() != null) {
                    nodes.add(outcome.get().node());
                } else if (outcome.get().excluded() != null) {
                    excluded.add(outcome.get().excluded());
                }
            }
        } catch (MetricsQueryGuard.MetricsUnavailableException e) {
            log.warn("노드 자원 수집 실패 — 저장소 응답 없음: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("노드 자원 수집 실패: {}", e.getMessage());
        }

        return new Snapshot(nodes, excluded);
    }

    /**
     * GPU 개수는 잡혔는데 GPU 축이 빈 까닭을 수집 1회당 한 줄로 남긴다 — 메트릭 이름과 노드 예시까지. 상한 초과(저장소 쪽)와
     * 빈 결과(익스포터가 그 카운터를 안 냄)는 확인할 자리가 달라 갈라 적는다. 응답의 0은 규약이지만 로그까지 조용하면
     * 운영자가 어디를 봐야 할지 모른다.
     */
    private void warnGpuTelemetryGaps(Map<String, Integer> gpuCounts, ExporterInstanceResolver.GpuTelemetryDiscovery d) {
        if (gpuCounts.isEmpty()) return;
        String sample = gpuCounts.keySet().stream().sorted().findFirst().orElse("?");
        if (!d.timedOutMetrics().isEmpty()) {
            log.warn("GPU 노드 {}대(예: {})의 {} 질의가 상한 {}ms 안에 끝나지 않았다 — 저장소 응답 지연. 이번 수집의 그 칸은 0 이다",
                    gpuCounts.size(), sample, d.timedOutMetrics(), properties.getMetrics().getQueryTimeoutMs());
        }
        if (!d.emptyMetrics().isEmpty()) {
            log.warn("GPU 노드 {}대(예: {})의 {} 시리즈가 비었다 — GPU 개수(DCGM_FI_DEV_GPU_UTIL)는 잡혔으니 익스포터가 그 카운터를 내는지 확인하십시오",
                    gpuCounts.size(), sample, d.emptyMetrics());
        }
    }

    /**
     * 노드 한 대의 메트릭. 핵심 메트릭 4종 중 하나라도 알 수 없으면 제외 사유를 담은 결과를 돌려준다.
     * 작업 스레드에서 돌므로 공유 상태를 건드리지 않는다.
     */
    private NodeOutcome collectNodeMetrics(String mdcId, String instance, String ipmiInstance, int totalGpu,
                                           GpuTelemetry gpuTelemetry, NodePowerSpecs specs) {
        String filter = String.format("{instance=\"%s\"}", instance);
        // 전력·온도는 IPMI 시리즈의 instance로 묻는다 — 노드 익스포터와 다를 수 있다.
        String ipmiFilter = String.format("{instance=\"%s\"}", ipmiInstance);
        String mount = properties.getDiskMountPoint();

        // ── 핵심 메트릭: 없으면 노드 자체를 수집하지 않는다 ─────────
        OptionalDouble cpuUsage = requiredQuery(String.format(
                "100 - (avg(irate(node_cpu_seconds_total{mode=\"idle\",instance=\"%s\"}[5m])) * 100)", instance));
        OptionalDouble cores = requiredQuery(String.format(
                "count(node_cpu_seconds_total{mode=\"idle\",instance=\"%s\"})", instance));
        OptionalDouble memTotal = requiredQuery("node_memory_MemTotal_bytes" + filter);
        OptionalDouble memAvail = requiredQuery("node_memory_MemAvailable_bytes" + filter);

        List<String> missing = new ArrayList<>();
        if (cpuUsage.isEmpty()) missing.add("cpu사용률");
        if (cores.isEmpty() || cores.getAsDouble() <= 0) missing.add("코어수");
        if (memTotal.isEmpty() || memTotal.getAsDouble() <= 0) missing.add("MemTotal");
        if (memAvail.isEmpty()) missing.add("MemAvailable");
        if (!missing.isEmpty()) {
            log.warn("노드 수집 제외: instance={} — 판단 불가 메트릭 {} (질의 실패 또는 시리즈 없음). "
                    + "0 으로 채우면 빈 노드로 오판하므로 후보에서 뺀다", instance, missing);
            return NodeOutcome.excluded(new ExcludedNode(mdcId, instance, missing));
        }

        double memUsage = (1 - memAvail.getAsDouble() / memTotal.getAsDouble()) * 100;
        int totalCpu = (int) cores.getAsDouble();
        long totalMemMb = (long) (memTotal.getAsDouble() / 1024 / 1024);

        // ── 부가 메트릭: 없으면 0 ────────────────────────────────
        // 마운트 포인트를 고정하지 않으면 여러 시리즈 중 첫 건이 잡혀 값이 흔들린다.
        // 총량과 가용량을 따로 받아 사용률은 여기서 계산한다 — 총량(GB)이 디스크 제약(C1)의 기준이 된다.
        // 총량을 못 얻으면 0 = "모름"이고, 그 노드는 디스크 검사를 받지 않는다(부가 메트릭이라 제외 사유가 아니다).
        double diskSize = safeQuery(String.format(
                "node_filesystem_size_bytes{fstype!~\"tmpfs|overlay\",mountpoint=\"%s\",instance=\"%s\"}", mount, instance));
        double diskAvail = safeQuery(String.format(
                "node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay\",mountpoint=\"%s\",instance=\"%s\"}", mount, instance));
        double diskUsage = diskSize > 0 ? (1 - diskAvail / diskSize) * 100 : 0;
        long diskCapacityGb = (long) (diskSize / 1024 / 1024 / 1024);

        double powerWatts = safeQuery("ipmi_dcmi_power_consumption_watts" + ipmiFilter);
        // 괄호를 닫지 않으면 PromQL 파싱 오류가 나고 값이 항상 0이 된다.
        double temperature = safeQuery(String.format(
                "avg(ipmi_temperature_celsius{name=~\"CPU.*\",instance=\"%s\"})", ipmiInstance));
        double networkRx = safeQuery("sum(irate(node_network_receive_bytes_total" + filter + "[5m]))");
        double networkTx = safeQuery("sum(irate(node_network_transmit_bytes_total" + filter + "[5m]))");

        NodeResource node = new NodeResource(mdcId, instance,
                totalCpu, totalMemMb, totalGpu,
                cpuUsage.getAsDouble(), memUsage, diskUsage,
                powerWatts, specs.capacityOr(instance, powerModel::capacityOf), temperature,
                networkRx, networkTx)
                .withDiskCapacityGb(diskCapacityGb)
                .withGpuTelemetry(totalGpu > 0 ? gpuTelemetry : GpuTelemetry.EMPTY);   // GPU 축은 섀시 전력·온도에 섞지 않는다

        // IPMI 값이 하나도 없으면 전력·온도 판단 근거가 없다는 뜻이다.
        node.setPowerSource(powerWatts > 0 || temperature > 0 ? PowerSource.IPMI : PowerSource.NONE);
        return NodeOutcome.of(node);
    }


    /**
     * 핵심 메트릭 질의 — "값이 없다"와 "값이 0이다"를 구분한다.
     *
     * <p>{@code queryScalar}는 질의 실패도 시리즈 없음도 0.0으로 돌려주므로 구분할 수 없다.
     * 결과 벡터를 직접 받아 비어 있으면 {@code empty}, 예외도 {@code empty}로 돌려준다.</p>
     */
    @SuppressWarnings("unchecked")
    private OptionalDouble requiredQuery(String promQL) {
        try {
            List<Map<String, Object>> results = vmClient.queryResults(promQL);
            if (results == null || results.isEmpty()) return OptionalDouble.empty();

            List<Object> value = (List<Object>) results.get(0).get("value");
            if (value == null || value.size() < 2) return OptionalDouble.empty();

            double parsed = Double.parseDouble(value.get(1).toString());
            return Double.isNaN(parsed) ? OptionalDouble.empty() : OptionalDouble.of(parsed);
        } catch (Exception e) {
            log.debug("핵심 메트릭 조회 실패: query={}, error={}", promQL, e.getMessage());
            return OptionalDouble.empty();
        }
    }

    /** 부가 메트릭 질의 — 없거나 실패하면 0. */
    private double safeQuery(String promQL) {
        try {
            return vmClient.queryScalar(promQL);
        } catch (Exception e) {
            log.debug("메트릭 조회 실패: query={}, error={}", promQL, e.getMessage());
            return 0;
        }
    }
}
