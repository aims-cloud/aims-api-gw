package com.innogrid.aims.scheduler.metrics;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.scheduler.config.SchedulerProperties;

/**
 * 스케줄러가 주입받는 게이트웨이 — 설정 {@code aims.scheduler.mock-enabled}로 실제 저장소와 가상 클러스터 중 하나에 위임한다.
 *
 * <p>팀의 다른 모듈은 서비스 안에서 {@code mockEnabled ? mock : live}로 가르는데(GPU 대시보드·자원 탐색기), 스케줄러는
 * 질의를 쓰는 서비스가 셋이라 그 분기를 이 한곳에 모았다. 플래그는 기동 시 읽는다 — 바꾸려면 환경변수
 * {@code AIMS_SCHEDULER_MOCK_ENABLED}를 고치고 재시작한다. 켜져 있으면 기동 로그에 WARN을 남기고
 * {@code GET /api/v1/scheduler/metrics-source}가 {@code synthetic}을 보여 준다.</p>
 */
@Component
public class SwitchingMetricsGateway implements SchedulerMetricsGateway {

    private static final Logger log = LoggerFactory.getLogger(SwitchingMetricsGateway.class);

    private final SchedulerProperties properties;
    private final SchedulerMetricsGateway live;
    private final SyntheticMetricsGateway synthetic;   // 플래그가 꺼져 있으면 null — 파일을 읽지도 않는다

    public SwitchingMetricsGateway(VictoriaMetricsClient client, SchedulerProperties properties) {
        this.properties = properties;
        this.live = new VictoriaMetricsGateway(client);
        if (properties.isMockEnabled()) {
            SyntheticCluster cluster = SyntheticCluster.load(properties.getSyntheticClusterPath());
            this.synthetic = new SyntheticMetricsGateway(cluster);
            log.warn("스케줄러 모의 메트릭 모드 ON — 실제 저장소 대신 가상 클러스터 '{}'({}, MDC {}곳·노드 {}대)를 봅니다. 운영에서는 AIMS_SCHEDULER_MOCK_ENABLED=false",
                    cluster.name(), properties.getSyntheticClusterPath(), cluster.mdcCount(), cluster.nodes().size());
        } else {
            this.synthetic = null;
        }
    }

    private SchedulerMetricsGateway current() { return synthetic != null ? synthetic : live; }

    @Override public Map<String, Object> query(String promQL) { return current().query(promQL); }
    @Override public List<Map<String, Object>> queryResults(String promQL) { return current().queryResults(promQL); }
    @Override public double queryScalar(String promQL) { return current().queryScalar(promQL); }
    @Override public List<Map<String, Object>> queryRangeResults(String promQL, Instant start, Instant end, String step) {
        return current().queryRangeResults(promQL, start, end, step);
    }
    @Override public String source() { return current().source(); }

    /** 표시 API용 — 어느 소스인지, 모의면 어떤 파일·규모인지. */
    public MetricsSource describe() {
        if (synthetic == null) return new MetricsSource(VictoriaMetricsGateway.SOURCE, false, null, null, null, null);
        SyntheticCluster c = synthetic.cluster();
        return new MetricsSource(SyntheticMetricsGateway.SOURCE, true, properties.getSyntheticClusterPath(), c.name(), c.mdcCount(), c.nodes().size());
    }

    public record MetricsSource(String source, boolean mockEnabled, String clusterFile, String clusterName, Integer mdcCount, Integer nodeCount) {}
}
