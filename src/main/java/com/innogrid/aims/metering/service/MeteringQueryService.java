package com.innogrid.aims.metering.service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.innogrid.aims.metering.entity.MeteringHourly;
import com.innogrid.aims.metering.repository.MeteringHourlyRepository;

/**
 * 미터링 집계 데이터 조회 서비스.
 */
@Service
public class MeteringQueryService {

    private final MeteringHourlyRepository repository;

    public MeteringQueryService(MeteringHourlyRepository repository) {
        this.repository = repository;
    }

    /** 조건별 미터링 데이터 조회 */
    public List<MeteringHourly> getUsage(String mdcId, String metricName,
                                         OffsetDateTime from, OffsetDateTime to) {
        if (mdcId != null && metricName != null) {
            return repository.findByMdcIdAndMetricNameAndTimeBetweenOrderByTimeDesc(
                    mdcId, metricName, from, to);
        } else if (mdcId != null) {
            return repository.findByMdcIdAndTimeBetweenOrderByTimeDesc(mdcId, from, to);
        }
        return repository.findByTimeBetweenOrderByTimeDesc(from, to);
    }

    /** MDC별 일간 평균 집계 (보고서용) */
    public List<Map<String, Object>> getDailySummary(String mdcId, OffsetDateTime from, OffsetDateTime to) {
        List<MeteringHourly> data = mdcId != null
                ? repository.findByMdcIdAndTimeBetweenOrderByTimeDesc(mdcId, from, to)
                : repository.findByTimeBetweenOrderByTimeDesc(from, to);

        // 날짜 + 메트릭별 그룹핑 → 일평균 계산
        return data.stream()
                .collect(Collectors.groupingBy(m ->
                        m.getTime().toLocalDate() + "|" + m.getMetricName()))
                .entrySet().stream()
                .map(entry -> {
                    String[] parts = entry.getKey().split("\\|");
                    List<MeteringHourly> rows = entry.getValue();
                    double avg = rows.stream().mapToDouble(MeteringHourly::getAvgValue).average().orElse(0);
                    double max = rows.stream().mapToDouble(MeteringHourly::getMaxValue).max().orElse(0);
                    double min = rows.stream().mapToDouble(MeteringHourly::getMinValue).min().orElse(0);

                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("date", parts[0]);
                    summary.put("metricName", parts[1]);
                    summary.put("avgValue", Math.round(avg * 100.0) / 100.0);
                    summary.put("maxValue", Math.round(max * 100.0) / 100.0);
                    summary.put("minValue", Math.round(min * 100.0) / 100.0);
                    summary.put("sampleCount", rows.size());
                    return summary;
                })
                .sorted((a, b) -> ((String) a.get("date")).compareTo((String) b.get("date")))
                .toList();
    }

    /** 저장소 상태 (총 레코드 수, 최신/최고 시간) */
    public Map<String, Object> getStorageStatus() {
        long totalRecords = repository.count();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("totalRecords", totalRecords);
        status.put("storageType", "PostgreSQL (metering_hourly)");

        if (totalRecords > 0) {
            OffsetDateTime from = OffsetDateTime.now().minusYears(2);
            OffsetDateTime to = OffsetDateTime.now();
            List<MeteringHourly> latest = repository.findByTimeBetweenOrderByTimeDesc(from, to);
            if (!latest.isEmpty()) {
                status.put("latestRecord", latest.get(0).getTime());
                status.put("oldestRecord", latest.get(latest.size() - 1).getTime());
            }
        }
        return status;
    }
}
