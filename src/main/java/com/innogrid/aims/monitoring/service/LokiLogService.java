package com.innogrid.aims.monitoring.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.innogrid.aims.monitoring.LokiClient;

@Service
public class LokiLogService {

    private final LokiClient lokiClient;

    public LokiLogService(LokiClient lokiClient) {
        this.lokiClient = lokiClient;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchLogs(String job, String level, String keyword,
                                                 Integer hours, Integer limit) {
        String logQL = buildLogQL(job, level, keyword);
        int queryLimit = limit != null ? limit : 100;
        Instant end = Instant.now();
        Instant start = end.minus(hours != null ? hours : 24, ChronoUnit.HOURS);

        List<Map<String, Object>> streams = lokiClient.queryRange(logQL, start, end, queryLimit);

        List<Map<String, Object>> logs = new ArrayList<>();
        for (Map<String, Object> stream : streams) {
            Map<String, String> labels = (Map<String, String>) stream.get("stream");
            List<List<String>> values = (List<List<String>>) stream.get("values");
            if (values == null) continue;

            for (List<String> entry : values) {
                Map<String, Object> log = new LinkedHashMap<>();
                log.put("timestamp", nanoToIso(entry.get(0)));
                log.put("message", entry.get(1));
                log.put("labels", labels);
                logs.add(log);
            }
        }
        return logs;
    }

    public List<String> getLogSources() {
        return lokiClient.labelValues("job");
    }

    public List<String> getLogLabels() {
        return lokiClient.labels();
    }

    public boolean isHealthy() {
        return lokiClient.isHealthy();
    }

    private static String nanoToIso(String nanoEpoch) {
        long nanos = Long.parseLong(nanoEpoch);
        Instant instant = Instant.ofEpochSecond(nanos / 1_000_000_000, nanos % 1_000_000_000);
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .withZone(ZoneOffset.UTC)
                .format(instant);
    }

    private String buildLogQL(String job, String level, String keyword) {
        StringBuilder sb = new StringBuilder("{");

        List<String> selectors = new ArrayList<>();
        if (job != null && !job.isBlank()) {
            selectors.add("job=\"" + job + "\"");
        }
        if (level != null && !level.isBlank()) {
            selectors.add("level=\"" + level.toLowerCase() + "\"");
        }

        if (selectors.isEmpty()) {
            sb.append("job=~\".+\"");
        } else {
            sb.append(String.join(",", selectors));
        }
        sb.append("}");

        if (keyword != null && !keyword.isBlank()) {
            sb.append(" |= \"").append(keyword).append("\"");
        }

        return sb.toString();
    }
}
