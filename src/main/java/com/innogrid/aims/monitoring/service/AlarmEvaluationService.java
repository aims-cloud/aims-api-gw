package com.innogrid.aims.monitoring.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.entity.AlarmHistory;
import com.innogrid.aims.monitoring.entity.AlarmRule;
import com.innogrid.aims.monitoring.entity.Event;
import com.innogrid.aims.monitoring.repository.AlarmHistoryRepository;
import com.innogrid.aims.monitoring.repository.AlarmRuleRepository;
import com.innogrid.aims.monitoring.repository.EventRepository;

/**
 * 알람 규칙을 주기적으로 평가하여 임계치 초과 시 알람과 이벤트를 생성한다.
 */
@Service
public class AlarmEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(AlarmEvaluationService.class);

    private static final Map<String, String> METRIC_PROMQL = Map.of(
            "cpu_usage", "100 - (avg by(instance, mdc_id) (irate(node_cpu_seconds_total{mode=\"idle\"}[5m])) * 100)",
            "mem_usage", "(1 - avg by(instance, mdc_id) (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)) * 100",
            "disk_usage", "(1 - avg by(instance, mdc_id) (node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay\"} / node_filesystem_size_bytes)) * 100",
            "gpu_temperature", "avg by(instance, mdc_id) (DCGM_FI_DEV_GPU_TEMP)"
    );

    private final AlarmRuleRepository ruleRepository;
    private final AlarmHistoryRepository historyRepository;
    private final EventRepository eventRepository;
    private final VictoriaMetricsClient vmClient;

    public AlarmEvaluationService(AlarmRuleRepository ruleRepository,
                                   AlarmHistoryRepository historyRepository,
                                   EventRepository eventRepository,
                                   VictoriaMetricsClient vmClient) {
        this.ruleRepository = ruleRepository;
        this.historyRepository = historyRepository;
        this.eventRepository = eventRepository;
        this.vmClient = vmClient;
    }

    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void evaluateRules() {
        List<AlarmRule> rules = ruleRepository.findByEnabledTrue();
        if (rules.isEmpty()) return;

        for (AlarmRule rule : rules) {
            String promQL = METRIC_PROMQL.get(rule.getMetricName());
            if (promQL == null) continue;

            try {
                Map<String, Object> response = vmClient.query(promQL);
                if (response == null) continue;

                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                if (data == null) continue;

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");
                if (results == null) continue;

                for (Map<String, Object> result : results) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> labels = (Map<String, String>) result.get("metric");
                    @SuppressWarnings("unchecked")
                    List<Object> value = (List<Object>) result.get("value");

                    if (value == null || value.size() < 2) continue;

                    double metricValue = Double.parseDouble(value.get(1).toString());
                    String instance = labels.getOrDefault("instance", "unknown");
                    String mdcId = labels.getOrDefault("mdc_id", "unknown");

                    if (isThresholdExceeded(rule.getCondition(), metricValue, rule.getThreshold())) {
                        AlarmHistory alarm = new AlarmHistory(rule, mdcId, instance,
                                metricValue, rule.getSeverity());
                        historyRepository.save(alarm);

                        Event event = new Event(mdcId, rule.getSeverity(), instance,
                                String.format("%s: %.1f (임계치: %s %.1f)",
                                        rule.getName(), metricValue, rule.getCondition(), rule.getThreshold()));
                        eventRepository.save(event);

                        log.info("알람 발생: rule={}, instance={}, value={}", rule.getName(), instance, metricValue);
                    }
                }
            } catch (Exception e) {
                log.debug("알람 평가 스킵: rule={}, error={}", rule.getName(), e.getMessage());
            }
        }
    }

    private boolean isThresholdExceeded(String condition, double value, double threshold) {
        return switch (condition) {
            case "gt" -> value > threshold;
            case "gte" -> value >= threshold;
            case "lt" -> value < threshold;
            case "lte" -> value <= threshold;
            case "eq" -> value == threshold;
            default -> false;
        };
    }
}
