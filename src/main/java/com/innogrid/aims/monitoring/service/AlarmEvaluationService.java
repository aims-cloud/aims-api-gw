package com.innogrid.aims.monitoring.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmSource;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;
import com.innogrid.aims.monitoring.entity.AlarmHistory;
import com.innogrid.aims.monitoring.entity.AlarmRule;
import com.innogrid.aims.monitoring.entity.Event;
import com.innogrid.aims.monitoring.repository.AlarmHistoryRepository;
import com.innogrid.aims.monitoring.repository.AlarmRuleRepository;
import com.innogrid.aims.monitoring.repository.EventRepository;
import com.innogrid.aims.monitoring.service.alarm.AlarmMetric;

/**
 * 알람 규칙을 주기적으로 평가하여 임계치 초과 시 알람 이력과 이벤트를 생성한다 (docs/09 A.4, C.2).
 *
 * <ul>
 *   <li>평가 단위는 규칙이 아니라 <b>메트릭</b>이다. 활성 규칙을 {@link AlarmMetric} 별로 묶어 메트릭당 Thanos 쿼리 한 번으로
 *       전체 자원 벡터를 받고, 메모리에서 규칙별 임계값에 대조한다. 주기당 쿼리 수 = 메트릭 종류 수로 규칙 수와 무관하다.</li>
 *   <li>PromQL 은 카탈로그의 표준식({@code AlarmMetric.promql})을 쓰므로 자원 탐색기 패널 수치와 같은 식으로 판정한다.
 *       규칙의 metricName 은 카탈로그 코드(예: {@code CPU_USED_PERCENT})나 초안 별칭({@code cpu_usage})이며,
 *       해석되지 않으면 그 규칙은 건너뛴다.</li>
 *   <li>자원 식별은 자원 탐색기와 같은 라벨을 쓴다 — {@code tb_ns/tb_infra/tb_node} 가 있으면 Tumblebug VM(영역 = Namespace),
 *       없으면 물리 호스트 {@code instance}(영역 = {@code mdc_id}).</li>
 *   <li>이력에는 카탈로그 코드·단위와 기준 문자열({@code CPU Used > 80%})을 스냅샷으로 남긴다.</li>
 * </ul>
 * 중복 억제(같은 규칙×자원 OPEN 이면 재생성 금지)와 해소(RESOLVED)는 다음 단계.
 */
@Service
public class AlarmEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(AlarmEvaluationService.class);

    /** 전체 자원 벡터를 자원 하나 단위로 집계하는 라벨 — 물리 호스트(instance, mdc_id) + Tumblebug VM(tb_*) */
    static final String GROUP_BY = "instance, mdc_id, tb_ns, tb_infra, tb_node";
    /** rate 창 — 1분 주기 평가에서 스크레이프 누락에 견디는 최소 창(자원 탐색기 rateWindow 하한과 동일) */
    static final String RATE_WINDOW = "5m";

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

        for (Map.Entry<AlarmMetric, List<AlarmRule>> group : groupByMetric(rules).entrySet()) {
            AlarmMetric metric = group.getKey();
            List<Sample> samples;
            try {
                samples = querySamples(metric);
            } catch (Exception e) {
                log.debug("알람 평가 스킵: metric={}, rules={}, error={}", metric, group.getValue().size(), e.getMessage());
                continue;
            }
            for (Sample sample : samples) {
                for (AlarmRule rule : group.getValue()) {
                    if (isThresholdExceeded(rule.getCondition(), sample.value(), rule.getThreshold())) {
                        record(rule, metric, sample);
                    }
                }
            }
        }
    }

    /** 활성 규칙을 카탈로그 메트릭별로 묶는다. 해석되지 않는 metricName 은 경고 후 제외 */
    static Map<AlarmMetric, List<AlarmRule>> groupByMetric(List<AlarmRule> rules) {
        Map<AlarmMetric, List<AlarmRule>> byMetric = new LinkedHashMap<>();
        for (AlarmRule rule : rules) {
            Optional<AlarmMetric> metric = AlarmMetric.resolve(rule.getMetricName());
            if (metric.isEmpty()) {
                log.warn("알람 규칙 스킵 — 카탈로그에 없는 메트릭: rule={}, metricName={}", rule.getName(), rule.getMetricName());
                continue;
            }
            byMetric.computeIfAbsent(metric.get(), k -> new ArrayList<>()).add(rule);
        }
        return byMetric;
    }

    /** 메트릭 하나의 전체 자원 현재값 — Prometheus 즉시 쿼리 응답(data.result[].metric/value)을 Sample 로 */
    private List<Sample> querySamples(AlarmMetric metric) {
        Map<String, Object> response = vmClient.query(metric.promql(GROUP_BY, null, RATE_WINDOW));
        if (response == null) return List.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        if (data == null) return List.of();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");
        if (results == null) return List.of();

        List<Sample> samples = new ArrayList<>(results.size());
        for (Map<String, Object> result : results) {
            @SuppressWarnings("unchecked")
            Map<String, String> labels = (Map<String, String>) result.get("metric");
            @SuppressWarnings("unchecked")
            List<Object> value = (List<Object>) result.get("value");
            if (labels == null || value == null || value.size() < 2) continue;
            double v;
            try {
                v = Double.parseDouble(value.get(1).toString());
            } catch (NumberFormatException e) {
                continue;               // NaN/Inf 문자열 등은 판정 대상에서 제외
            }
            if (Double.isNaN(v) || Double.isInfinite(v)) continue;
            samples.add(new Sample(labels, v));
        }
        return samples;
    }

    /** 재정의된 alarm_history 원장에 규칙 스냅샷으로 기록하고 events 원장에도 발행 — docs/09 C.1 */
    private void record(AlarmRule rule, AlarmMetric metric, Sample sample) {
        String criterion = metric.criterion(symbolOf(rule.getCondition()), rule.getThreshold());
        AlarmHistory.Builder builder = AlarmHistory.builder(AlarmSource.POLICY, levelOf(rule.getSeverity()), criterion)
                .policy(rule.getId(), rule.getName())
                .metric(metric.name())
                .observed(sample.value(), metric.unit());

        String nsId = sample.label("tb_ns");
        String nodeId = sample.label("tb_node");
        String mdcId = sample.label("mdc_id");
        if (nsId != null && nodeId != null) {
            builder.resource("vm", "TB:" + nsId + "/" + sample.label("tb_infra") + "/" + nodeId, nodeId).namespace(nsId);
        } else {
            String instance = sample.labels().getOrDefault("instance", "unknown");
            builder.resource("node", instance, instance).mdc(mdcId);
        }
        AlarmHistory alarm = historyRepository.save(builder.build());

        Event event = new Event(mdcId == null ? "unknown" : mdcId, rule.getSeverity(), alarm.getResourceName(),
                String.format("%s: %s (관측값 %.1f%s)", rule.getName(), criterion, sample.value(), metric.unit()));
        eventRepository.save(event);

        log.info("알람 발생: rule={}, resource={}, value={}", rule.getName(), alarm.getResourceId(), sample.value());
    }

    /** 초안 규칙의 severity 문자열(INFO/WARNING/CRITICAL, 대소문자 무관) → AlarmLevel. 알 수 없으면 WARNING */
    static AlarmLevel levelOf(String severity) {
        if (severity == null) return AlarmLevel.WARNING;
        try {
            return AlarmLevel.valueOf(severity.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return AlarmLevel.WARNING;
        }
    }

    /** 규칙 condition 코드(gt/gte/lt/lte/eq, 대소문자 무관) → 기준 문자열 연산자 기호 */
    static String symbolOf(String condition) {
        if (condition == null) return "?";
        return switch (condition.trim().toLowerCase()) {
            case "gt" -> ">";
            case "gte" -> ">=";
            case "lt" -> "<";
            case "lte" -> "<=";
            case "eq" -> "=";
            default -> condition;
        };
    }

    static boolean isThresholdExceeded(String condition, double value, double threshold) {
        if (condition == null) return false;
        return switch (condition.trim().toLowerCase()) {
            case "gt" -> value > threshold;
            case "gte" -> value >= threshold;
            case "lt" -> value < threshold;
            case "lte" -> value <= threshold;
            case "eq" -> value == threshold;
            default -> false;
        };
    }

    /** 자원 하나의 현재값과 식별 라벨 */
    record Sample(Map<String, String> labels, double value) {
        String label(String name) {
            String v = labels.get(name);
            return v == null || v.isBlank() ? null : v;
        }
    }
}
