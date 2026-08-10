package com.innogrid.aims.metering.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.metering.entity.MeteringHourly;
import com.innogrid.aims.metering.repository.MeteringHourlyRepository;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;

/**
 * 매시간 VictoriaMetrics에서 메트릭을 집계하여 PostgreSQL metering_hourly 테이블에 적재한다.
 * 매일 13개월 이전 데이터를 삭제한다.
 */
@Service
public class MeteringAggregationService {

    private static final Logger log = LoggerFactory.getLogger(MeteringAggregationService.class);

    private static final String[] METRICS = {
            "cpu_usage",
            "mem_usage",
            "disk_usage",
            "network_rx_bytes",
            "network_tx_bytes"
    };

    private static final Map<String, String> PROMQL_MAP = Map.of(
            "cpu_usage",
            "100 - (avg by(instance, mdc_id) (irate(node_cpu_seconds_total{mode=\"idle\"}[1h])) * 100)",
            "mem_usage",
            "(1 - avg by(instance, mdc_id) (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)) * 100",
            "disk_usage",
            "(1 - avg by(instance, mdc_id) (node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay\"} / node_filesystem_size_bytes)) * 100",
            "network_rx_bytes",
            "sum by(instance, mdc_id) (increase(node_network_receive_bytes_total[1h]))",
            "network_tx_bytes",
            "sum by(instance, mdc_id) (increase(node_network_transmit_bytes_total[1h]))"
    );

    private final VictoriaMetricsClient vmClient;
    private final MeteringHourlyRepository repository;

    public MeteringAggregationService(VictoriaMetricsClient vmClient,
                                      MeteringHourlyRepository repository) {
        this.vmClient = vmClient;
        this.repository = repository;
    }

    /**
     * 매시간 정각에 지난 1시간의 메트릭을 집계한다.
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void aggregateHourly() {
        Instant end = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant start = end.minus(1, ChronoUnit.HOURS);
        OffsetDateTime time = end.atOffset(ZoneOffset.UTC);

        log.info("미터링 시간별 집계 시작: {} ~ {}", start, end);

        for (String metric : METRICS) {
            String promQL = PROMQL_MAP.get(metric);
            if (promQL == null) continue;

            try {
                List<Map<String, Object>> results = vmClient.queryRangeResults(promQL, start, end, "1h");
                for (Map<String, Object> result : results) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> labels = (Map<String, String>) result.get("metric");
                    String mdcId = labels.getOrDefault("mdc_id", "unknown");
                    String instance = labels.getOrDefault("instance", "unknown");

                    @SuppressWarnings("unchecked")
                    List<List<Object>> values = (List<List<Object>>) result.get("values");
                    if (values == null || values.isEmpty()) continue;

                    double sum = 0, max = Double.MIN_VALUE, min = Double.MAX_VALUE;
                    for (List<Object> v : values) {
                        double val = Double.parseDouble(v.get(1).toString());
                        sum += val;
                        max = Math.max(max, val);
                        min = Math.min(min, val);
                    }
                    double avg = sum / values.size();

                    repository.save(new MeteringHourly(
                            time, mdcId, instance, metric,
                            avg, max, min, values.size()
                    ));
                }
                log.debug("집계 완료: metric={}, results={}", metric, results.size());
            } catch (Exception e) {
                log.warn("집계 실패: metric={}, error={}", metric, e.getMessage());
            }
        }

        log.info("미터링 시간별 집계 완료");
    }

    /**
     * 매일 자정에 13개월 이전 데이터를 삭제한다.
     */
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void cleanupOldData() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMonths(13);
        int deleted = repository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("미터링 데이터 정리: {}건 삭제 (기준: {})", deleted, cutoff);
        }
    }
}
