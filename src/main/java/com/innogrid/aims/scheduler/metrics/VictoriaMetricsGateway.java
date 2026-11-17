package com.innogrid.aims.scheduler.metrics;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;

/** 기존 메트릭 클라이언트에 그대로 위임하는 기본 구현 — 동작·예외·시간 상한 모두 종전과 같다. */
public final class VictoriaMetricsGateway implements SchedulerMetricsGateway {

    public static final String SOURCE = "victoria";

    private final VictoriaMetricsClient client;

    public VictoriaMetricsGateway(VictoriaMetricsClient client) {
        this.client = client;
    }

    /** 클라이언트가 null이면(추세 없이 조립한 테스트 경로) null을 돌려준다. */
    public static SchedulerMetricsGateway of(VictoriaMetricsClient client) {
        return client == null ? null : new VictoriaMetricsGateway(client);
    }

    @Override public Map<String, Object> query(String promQL) { return client.query(promQL); }
    @Override public List<Map<String, Object>> queryResults(String promQL) { return client.queryResults(promQL); }
    @Override public double queryScalar(String promQL) { return client.queryScalar(promQL); }
    @Override public List<Map<String, Object>> queryRangeResults(String promQL, Instant start, Instant end, String step) {
        return client.queryRangeResults(promQL, start, end, step);
    }
    @Override public String source() { return SOURCE; }
}
