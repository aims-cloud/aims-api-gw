package com.innogrid.aims.monitoring;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.innogrid.aims.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 로그(streams)와 집계(vector/matrix)를 구분하고 실패를 빈 결과로 바꾸지 않는 Loki 클라이언트. */
@Component
public class LokiQueryClient {
    private final RestClient client;

    @Autowired
    public LokiQueryClient(LokiProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        client = RestClient.builder().baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
    }

    LokiQueryClient(RestClient client) { this.client = client; }

    public record Point(Map<String, String> labels, Instant time, long count) {}
    public record LogLine(Map<String, String> labels, Instant time, String message) {}
    public record Response(String status, Data data) {}
    public record Data(String resultType, List<Result> result) {}
    public record Result(Map<String, String> metric, Map<String, String> stream,
                         List<String> value, List<List<String>> values) {}

    public List<Point> instant(String query, Instant time) {
        return points(fetch("/loki/api/v1/query?query={q}&time={t}", "vector", query, time.toString()), false);
    }

    public List<Point> range(String query, Instant start, Instant end, long step) {
        return points(fetch("/loki/api/v1/query_range?query={q}&start={s}&end={e}&step={step}",
                "matrix", query, start.toString(), end.toString(), step), true);
    }

    public List<LogLine> logs(String query, Instant start, Instant end, int limit) {
        List<Result> results = fetch("/loki/api/v1/query_range?query={q}&start={s}&end={e}&limit={l}&direction=backward",
                "streams", query, start.toString(), end.toString(), limit);
        try {
            List<LogLine> lines = new ArrayList<>();
            for (Result result : results) {
                if (result.stream() == null || result.values() == null) throw new IllegalArgumentException();
                for (List<String> value : result.values()) {
                    BigInteger[] ns = new BigInteger(value.get(0)).divideAndRemainder(BigInteger.valueOf(1_000_000_000));
                    lines.add(new LogLine(result.stream(), Instant.ofEpochSecond(ns[0].longValueExact(), ns[1].longValueExact()), value.get(1)));
                }
            }
            return lines;
        } catch (RuntimeException e) { throw unavailable(); }
    }

    private List<Result> fetch(String uri, String type, Object... args) {
        try {
            Response response = client.get().uri(uri, args).retrieve().body(Response.class);
            if (response == null || !"success".equals(response.status()) || response.data() == null
                    || !type.equals(response.data().resultType()) || response.data().result() == null)
                throw new IllegalArgumentException();
            return response.data().result();
        } catch (RuntimeException e) { throw unavailable(); }
    }

    private List<Point> points(List<Result> results, boolean matrix) {
        try {
            List<Point> points = new ArrayList<>();
            for (Result result : results) {
                if (result.metric() == null) throw new IllegalArgumentException();
                List<List<String>> values = matrix ? result.values() : List.of(result.value());
                for (List<String> value : values) {
                    BigDecimal seconds = new BigDecimal(value.get(0));
                    long count = new BigDecimal(value.get(1)).longValueExact();
                    if (count < 0) throw new IllegalArgumentException();
                    points.add(new Point(result.metric(), Instant.ofEpochSecond(seconds.longValue(),
                            seconds.remainder(BigDecimal.ONE).movePointRight(9).longValue()), count));
                }
            }
            return points;
        } catch (RuntimeException e) { throw unavailable(); }
    }

    private static BusinessException unavailable() {
        return new BusinessException(HttpStatus.BAD_GATEWAY, "Loki 로그 조회에 실패했습니다.");
    }
}
