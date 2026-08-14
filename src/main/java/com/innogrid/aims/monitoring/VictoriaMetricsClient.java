package com.innogrid.aims.monitoring;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * VictoriaMetrics PromQL 쿼리 클라이언트.
 * Prometheus-compatible HTTP API를 사용한다.
 */
@Component
public class VictoriaMetricsClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public VictoriaMetricsClient(VictoriaMetricsProperties props) {
        this.restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .build();
    }

    /**
     * 즉시 쿼리 — 현재 시점의 값을 반환.
     * GET /api/v1/query?query={promQL}
     */
    public Map<String, Object> query(String promQL) {
        return restClient.get()
                .uri("/api/v1/query?query={q}", promQL)
                .retrieve()
                .body(MAP_TYPE);
    }

    /**
     * 범위 쿼리 — 시간 범위의 시계열 데이터를 반환.
     * GET /api/v1/query_range?query={promQL}&start={start}&end={end}&step={step}
     */
    public Map<String, Object> queryRange(String promQL, Instant start, Instant end, String step) {
        return restClient.get()
                .uri("/api/v1/query_range?query={q}&start={s}&end={e}&step={st}",
                        promQL, start.getEpochSecond(), end.getEpochSecond(), step)
                .retrieve()
                .body(MAP_TYPE);
    }

    /**
     * 범위 쿼리 결과에서 값 목록을 추출한다.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> queryRangeResults(String promQL, Instant start, Instant end, String step) {
        Map<String, Object> response = queryRange(promQL, start, end, step);
        if (response == null) return Collections.emptyList();

        Map<String, Object> data = (Map<String, Object>) response.get("data");
        if (data == null) return Collections.emptyList();

        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");
        return results != null ? results : Collections.emptyList();
    }

    /**
     * 즉시 쿼리 결과에서 단일 스칼라 값을 추출한다.
     */
    @SuppressWarnings("unchecked")
    public double queryScalar(String promQL) {
        Map<String, Object> response = query(promQL);
        if (response == null) return 0.0;

        Map<String, Object> data = (Map<String, Object>) response.get("data");
        if (data == null) return 0.0;

        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");
        if (results == null || results.isEmpty()) return 0.0;

        List<Object> value = (List<Object>) results.get(0).get("value");
        if (value == null || value.size() < 2) return 0.0;

        return Double.parseDouble(value.get(1).toString());
    }

    /**
     * 헬스체크.
     */
    public boolean isHealthy() {
        try {
            restClient.get().uri("/health").retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}
