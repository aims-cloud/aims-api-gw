package com.innogrid.aims.monitoring;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.innogrid.aims.monitoring.log.IntegrationHealth;
import com.innogrid.aims.monitoring.log.PortalLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * VictoriaMetrics(Thanos) PromQL 쿼리 클라이언트.
 * Prometheus-compatible HTTP API를 사용한다.
 *
 * 연동 상태는 {@link IntegrationHealth}("Thanos 연동", 키 {@code query})로 추적한다 — I/O 오류·5xx·429 를 장애로 보고
 * 정상↔장애 전이에서만 포털 로그 SYSTEM 행을 남긴다(docs/08 §6). 그 외 4xx(잘못된 PromQL 등)는 호출자 문제라 상태에 넣지 않는다.
 * 예외는 그대로 다시 던지므로 호출자의 처리 방식은 바뀌지 않는다.
 */
@Component
public class VictoriaMetricsClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    static final String HEALTH_SCREEN = "Thanos 연동";
    static final String HEALTH_KEY = "query";
    static final String HEALTH_WHAT = "Thanos 조회";

    private final RestClient restClient;
    private final IntegrationHealth health;

    @Autowired
    public VictoriaMetricsClient(VictoriaMetricsProperties props, PortalLogService portalLogService) {
        this(RestClient.builder().baseUrl(props.getBaseUrl()).build(), portalLogService);
    }

    /** 테스트용 — 미리 만든 RestClient 로 */
    VictoriaMetricsClient(RestClient restClient, PortalLogService portalLogService) {
        this.restClient = restClient;
        this.health = new IntegrationHealth(portalLogService, HEALTH_SCREEN);
    }

    /**
     * 즉시 쿼리 — 현재 시점의 값을 반환.
     * GET /api/v1/query?query={promQL}
     */
    public Map<String, Object> query(String promQL) {
        return observe(() -> restClient.get()
                .uri("/api/v1/query?query={q}", promQL)
                .retrieve()
                .body(MAP_TYPE));
    }

    /** 즉시 쿼리 결과의 벡터 목록을 반환한다. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> queryResults(String promQL) {
        Map<String, Object> response = query(promQL);
        if (response == null) return Collections.emptyList();

        Map<String, Object> data = (Map<String, Object>) response.get("data");
        if (data == null) return Collections.emptyList();

        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");
        return results != null ? results : Collections.emptyList();
    }

    /**
     * 시점 지정 즉시 쿼리의 벡터 목록 — {@code *_over_time(...[창])} 을 창의 끝 시각에 평가할 때 쓴다.
     * GET /api/v1/query?query={promQL}&time={epochSeconds}
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> queryResultsAt(String promQL, Instant time) {
        Map<String, Object> response = observe(() -> restClient.get()
                .uri("/api/v1/query?query={q}&time={t}", promQL, time.getEpochSecond())
                .retrieve()
                .body(MAP_TYPE));
        if (response == null) return Collections.emptyList();

        Map<String, Object> data = (Map<String, Object>) response.get("data");
        if (data == null) return Collections.emptyList();

        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");
        return results != null ? results : Collections.emptyList();
    }

    /**
     * 범위 쿼리 — 시간 범위의 시계열 데이터를 반환.
     * GET /api/v1/query_range?query={promQL}&start={start}&end={end}&step={step}
     */
    public Map<String, Object> queryRange(String promQL, Instant start, Instant end, String step) {
        return observe(() -> restClient.get()
                .uri("/api/v1/query_range?query={q}&start={s}&end={e}&step={st}",
                        promQL, start.getEpochSecond(), end.getEpochSecond(), step)
                .retrieve()
                .body(MAP_TYPE));
    }

    /** 호출 결과를 연동 상태에 반영 — 성공이면 복구 확인, 연결·서버·속도 제한 오류면 장애 기록. 예외는 그대로 던진다 */
    private <T> T observe(Supplier<T> call) {
        T result;
        try {
            result = call.get();
        } catch (ResourceAccessException | HttpServerErrorException | HttpClientErrorException.TooManyRequests e) {
            health.failed(HEALTH_KEY, HEALTH_WHAT, e);
            throw e;
        }
        health.recovered(HEALTH_KEY, HEALTH_WHAT);
        return result;
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
