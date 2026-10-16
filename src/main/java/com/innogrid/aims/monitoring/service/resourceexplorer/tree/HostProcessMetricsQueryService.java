package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.monitoring.ThanosResults;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.process.ProcessMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 자원 1개의 Process 카테고리 패널 지표를 Thanos 에서 읽는다 — 프로세스 집계 현재값과 시계열 전용.
 *
 * node_exporter stat 콜렉터({@code node_procs_running, node_procs_blocked, node_forks_total})는 기본이고,
 * processes 콜렉터({@code node_processes_pids, node_processes_threads, node_processes_state{state}})는
 * {@code --collector.processes} 가 켜진 노드에서만 있다 — 없으면 null/빈 목록으로 내려 화면이 "-" 로 표시한다.
 * PID 별 목록·명령행은 기본 node_exporter 로 얻을 수 없어 제공하지 않는다(docs/06 §5.4).
 * 자원은 {@link MetricSelector} 로 가리키고, Thanos 장애나 시계열 부재는 null/빈 목록(미수집)으로 돌려준다.
 */
@Component
public class HostProcessMetricsQueryService {

    private static final Logger log = LoggerFactory.getLogger(HostProcessMetricsQueryService.class);
    private static final String RATE_WINDOW = "5m";

    /** 상태별 프로세스 수 1건 — state 는 라벨 그대로 */
    public record StateCount(String state, int count) {}

    /** KPI 현재값 — null 은 미수집(processes 콜렉터 미설치 포함), 상태별은 빈 목록 */
    public record ProcessSummary(Integer running, Integer blocked, Integer processes, Integer threads,
                                 Double forksPerSec, List<StateCount> byState) {
        public static final ProcessSummary EMPTY = new ProcessSummary(null, null, null, null, null, List.of());
    }

    private final VictoriaMetricsClient client;

    public HostProcessMetricsQueryService(VictoriaMetricsClient client) {
        this.client = client;
    }

    // ── PromQL ──────────────────────────────────────────────────

    static String running(MetricSelector s) {
        return "node_procs_running" + s.braces();
    }

    static String blocked(MetricSelector s) {
        return "node_procs_blocked" + s.braces();
    }

    static String processes(MetricSelector s) {
        return "node_processes_pids" + s.braces();
    }

    static String threads(MetricSelector s) {
        return "node_processes_threads" + s.braces();
    }

    static String forksPerSec(MetricSelector s, String window) {
        return "rate(node_forks_total" + s.braces() + "[" + window + "])";
    }

    static String byState(MetricSelector s) {
        return "node_processes_state" + s.braces();
    }

    static String trendQuery(ProcessMetric metric, MetricSelector s, String window) {
        return switch (metric) {
            case PROCS_RUNNING -> running(s);
            case PROCS_BLOCKED -> blocked(s);
            case PROCESSES_TOTAL -> processes(s);
            case THREADS -> threads(s);
            case FORKS_RATE -> forksPerSec(s, window);
        };
    }

    // ── 조회 ────────────────────────────────────────────────────

    /** KPI 현재값 — 개수는 정수, fork 는 소수 2자리. 장애는 전부 null */
    public ProcessSummary summary(MetricSelector s) {
        try {
            List<StateCount> states = new ArrayList<>();
            for (Map<String, Object> row : client.queryResults(byState(s))) {
                String state = ThanosResults.label(row, "state");
                Double count = ThanosResults.value(row);
                if (state != null && count != null) {
                    states.add(new StateCount(state, (int) Math.round(count)));
                }
            }
            states.sort(Comparator.comparing(StateCount::state));
            return new ProcessSummary(
                    toInt(scalar(running(s))),
                    toInt(scalar(blocked(s))),
                    toInt(scalar(processes(s))),
                    toInt(scalar(threads(s))),
                    round2(scalar(forksPerSec(s, RATE_WINDOW))),
                    List.copyOf(states));
        } catch (RuntimeException e) {
            log.warn("Thanos Process KPI 조회 실패 — 카드를 미수집으로 표시합니다: {}", e.getMessage());
            return ProcessSummary.EMPTY;
        }
    }

    /** 지표별 시계열 — 미수집이면 빈 목록. 개수는 정수로, fork 는 소수 2자리 */
    public List<DataPoint> trend(ProcessMetric metric, MetricSelector s, Instant from, Instant to, String step) {
        String query = trendQuery(metric, s, HostDetailMetricsQueryService.rateWindow(step));
        boolean rate = metric == ProcessMetric.FORKS_RATE;
        try {
            List<Map<String, Object>> results = client.queryRangeResults(query, from, to, step);
            if (results.isEmpty()) {
                return List.of();
            }
            List<DataPoint> points = new ArrayList<>();
            for (ThanosResults.Sample sample : ThanosResults.values(results.get(0))) {
                Double value = sample.value();
                points.add(new DataPoint(sample.epochSecond(), rate ? round2(value) : (double) Math.round(value)));
            }
            return points;
        } catch (RuntimeException e) {
            log.warn("Thanos Process 차트 조회 실패 — 빈 시계열을 돌려줍니다: {}", e.getMessage());
            return List.of();
        }
    }

    // ── 내부 ────────────────────────────────────────────────────

    private Double scalar(String query) {
        return ThanosResults.firstValue(client.queryResults(query));
    }

    private static Integer toInt(Double value) {
        return value == null ? null : (int) Math.round(value);
    }

    private static Double round2(Double value) {
        return value == null ? null : Math.round(value * 100) / 100.0;
    }
}
