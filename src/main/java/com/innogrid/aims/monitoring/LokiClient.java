package com.innogrid.aims.monitoring;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class LokiClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public LokiClient(LokiProperties props) {
        this.restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .build();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> queryRange(String logQL, Instant start, Instant end, int limit) {
        Map<String, Object> response = restClient.get()
                .uri("/loki/api/v1/query_range?query={q}&start={s}&end={e}&limit={l}",
                        logQL,
                        String.valueOf(start.getEpochSecond()),
                        String.valueOf(end.getEpochSecond()),
                        String.valueOf(limit))
                .retrieve()
                .body(MAP_TYPE);

        return extractStreams(response);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> query(String logQL, int limit) {
        Map<String, Object> response = restClient.get()
                .uri("/loki/api/v1/query?query={q}&limit={l}", logQL, String.valueOf(limit))
                .retrieve()
                .body(MAP_TYPE);

        return extractStreams(response);
    }

    @SuppressWarnings("unchecked")
    public List<String> labels() {
        Map<String, Object> response = restClient.get()
                .uri("/loki/api/v1/labels")
                .retrieve()
                .body(MAP_TYPE);

        if (response == null) return Collections.emptyList();
        List<String> data = (List<String>) response.get("data");
        return data != null ? data : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public List<String> labelValues(String label) {
        Map<String, Object> response = restClient.get()
                .uri("/loki/api/v1/label/{label}/values", label)
                .retrieve()
                .body(MAP_TYPE);

        if (response == null) return Collections.emptyList();
        List<String> data = (List<String>) response.get("data");
        return data != null ? data : Collections.emptyList();
    }

    public boolean isHealthy() {
        try {
            restClient.get().uri("/ready").retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractStreams(Map<String, Object> response) {
        if (response == null) return Collections.emptyList();

        Map<String, Object> data = (Map<String, Object>) response.get("data");
        if (data == null) return Collections.emptyList();

        List<Map<String, Object>> result = (List<Map<String, Object>>) data.get("result");
        return result != null ? result : Collections.emptyList();
    }
}
