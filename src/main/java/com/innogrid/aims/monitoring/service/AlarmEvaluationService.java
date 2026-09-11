package com.innogrid.aims.monitoring.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.monitoring.AlarmProperties;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmHistoryStatus;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmSource;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyType;
import com.innogrid.aims.monitoring.entity.AlarmHistory;
import com.innogrid.aims.monitoring.entity.AlarmPolicy;
import com.innogrid.aims.monitoring.repository.AlarmHistoryRepository;
import com.innogrid.aims.monitoring.repository.AlarmPolicyRepository;
import com.innogrid.aims.monitoring.service.alarm.AlarmEvaluationHealth;
import com.innogrid.aims.monitoring.service.alarm.AlarmMetric;
import com.innogrid.aims.monitoring.service.alarm.AlarmPolicyTargets;
import com.innogrid.aims.monitoring.service.alarm.AlarmPolicyTargets.Target;
import com.innogrid.aims.monitoring.service.alarm.AlarmStateChangedEvent;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;

/**
 * 알람 정책을 주기적으로 평가하여 임계치 초과 시 알람 이력과 이벤트를 생성한다 (docs/09 A.4, A.5.2, C.2).
 *
 * <ul>
 *   <li>평가 단위는 정책이 아니라 <b>메트릭</b>이다. 활성 정책을 {@link AlarmMetric} 별로 묶어 메트릭당 Thanos 쿼리 한 번으로
 *       전체 자원 벡터를 받고, 메모리에서 정책별 대상·임계값에 대조한다. 주기당 쿼리 수 = 메트릭 종류 수로 정책 수와 무관하다.
 *       주기·rate 창은 {@link AlarmProperties}({@code aims.alarm.evaluation.*}, 기본 60초·5분).</li>
 *   <li>정책의 대상 자원은 {@link AlarmPolicyTargets} 로 역할 스코프에서 전개한다(주기마다 역할 전체를 한 번 읽음).
 *       표본은 호스트면 node_exporter instance 라벨, VM 이면 {@code TB:{ns}/{infra}/{node}} 로 대상에 대조하고,
 *       대상이 아닌 표본은 건너뛴다. 이력의 자원 id 는 자원 탐색기와 같은 값({@code assets.id} / TB id)이다.</li>
 *   <li>PromQL 은 카탈로그의 표준식({@code AlarmMetric.promql})을 쓰므로 자원 탐색기 패널 수치와 같은 식으로 판정한다.</li>
 *   <li><b>지속 시간</b>: 정책의 durationSeconds 가 0 보다 크면 처음 초과한 시각부터 그 시간이 지나야 발생시킨다.
 *       대기 상태는 메모리에만 두므로 재기동 시 다시 센다(단일 인스턴스 전제, ShedLock 도입 시 함께 재검토).</li>
 *   <li><b>중복 억제·해소</b>: 정책×자원 지문({@code fingerprint} = SHA-256(정책 id|메트릭|자원 id))으로 미해소(OPEN·ACKNOWLEDGED)
 *       행을 찾아, 계속 초과면 새 행을 만들지 않고, 임계값 안으로 돌아오면 그 행을 RESOLVED 로 닫고 해소 이벤트를 발행한다.
 *       표본이 사라진 자원(exporter 다운 등)은 판정하지 않고 열어 둔다 — 수집 중단은 시스템 규칙(데드맨)의 몫이다.</li>
 *   <li>이력에는 정책 id·이름, 역할, 영역, 카탈로그 코드·단위, 기준 문자열({@code CPU Used > 80%})을 스냅샷으로 남긴다.</li>
 *   <li>발생·해소 전이는 {@link AlarmStateChangedEvent} 로 발행한다(docs/09 B.4.6). 대외 이벤트 원장({@code events}) 적재·전송·
 *       조치 훅은 구독자가 평가 트랜잭션 커밋 뒤에 처리하므로 평가기는 원장 두 개를 직접 쓰지 않는다.</li>
 *   <li>역할 스코프·Thanos 조회 실패는 {@link AlarmEvaluationHealth} 가 상태 전이(정상↔실패)에서만 포털 로그 SYSTEM 으로 남긴다.
 *       실패한 단위는 그 주기만 건너뛰고 나머지 메트릭은 계속 평가한다.</li>
 * </ul>
 */
@Service
public class AlarmEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(AlarmEvaluationService.class);

    /** 전체 자원 벡터를 자원 하나 단위로 집계하는 라벨 — 물리 호스트(instance, mdc_id) + Tumblebug VM(tb_*) */
    static final String GROUP_BY = "instance, mdc_id, tb_ns, tb_infra, tb_node";

    private final AlarmPolicyRepository policyRepository;
    private final AlarmHistoryRepository historyRepository;
    private final VictoriaMetricsClient vmClient;
    private final ResourceScopeService resourceScopeService;
    private final ApplicationEventPublisher eventPublisher;
    private final AlarmEvaluationHealth health;
    private final AlarmProperties.Evaluation settings;

    /** 지속 시간 대기 — 지문 → 처음 초과를 본 시각. 초과가 풀리거나 발생시키면 지운다 */
    private final Map<String, Instant> pendingSince = new ConcurrentHashMap<>();

    public AlarmEvaluationService(AlarmPolicyRepository policyRepository,
                                  AlarmHistoryRepository historyRepository,
                                  VictoriaMetricsClient vmClient,
                                  ResourceScopeService resourceScopeService,
                                  ApplicationEventPublisher eventPublisher,
                                  AlarmEvaluationHealth health,
                                  AlarmProperties properties) {
        this.policyRepository = policyRepository;
        this.historyRepository = historyRepository;
        this.vmClient = vmClient;
        this.resourceScopeService = resourceScopeService;
        this.eventPublisher = eventPublisher;
        this.health = health;
        this.settings = properties.getEvaluation();
    }

    /** 평가 주기 — aims.alarm.evaluation.interval-seconds(기본 60). 기동 시 고정 */
    @Scheduled(fixedRateString = "${aims.alarm.evaluation.interval-seconds:60}", timeUnit = TimeUnit.SECONDS)
    @Transactional
    public void evaluatePolicies() {
        List<AlarmPolicy> policies = policyRepository.findByEnabledTrueAndType(AlarmPolicyType.METRIC);
        if (policies.isEmpty()) return;

        Set<String> roles = new LinkedHashSet<>();
        policies.forEach(p -> roles.add(p.getRoleName()));
        Scope scope;
        try {
            scope = resourceScopeService.scopeOf(roles);
            health.recovered(AlarmEvaluationHealth.SCOPE_KEY, SCOPE_WHAT);
        } catch (RuntimeException e) {
            health.failed(AlarmEvaluationHealth.SCOPE_KEY, SCOPE_WHAT, e);
            return;                                                  // 대상을 알 수 없으면 이 주기는 전부 건너뛴다
        }
        Map<AlarmPolicy, PolicyTargets> targets = new LinkedHashMap<>();
        for (AlarmPolicy p : policies) targets.put(p, PolicyTargets.of(AlarmPolicyTargets.resolve(p, scope)));

        Instant now = Instant.now();
        for (Map.Entry<AlarmMetric, List<AlarmPolicy>> group : groupByMetric(policies).entrySet()) {
            AlarmMetric metric = group.getKey();
            List<Sample> samples;
            try {
                samples = querySamples(metric);
                health.recovered(AlarmEvaluationHealth.metricKey(metric), thanosWhat(metric));
            } catch (Exception e) {
                health.failed(AlarmEvaluationHealth.metricKey(metric), thanosWhat(metric), e);
                continue;                                            // 이 메트릭만 건너뛰고 다음 메트릭은 평가
            }
            Map<String, AlarmHistory> active = activeByFingerprint(metric);
            for (Sample sample : samples) {
                for (AlarmPolicy policy : group.getValue()) {
                    Target target = targets.get(policy).match(sample);
                    if (target == null) continue;                       // 이 정책의 대상이 아닌 자원

                    String fingerprint = fingerprint(policy, metric, target.resourceId());
                    AlarmHistory open = active.get(fingerprint);
                    boolean exceeded = policy.getOperator().test(sample.value(), policy.getThreshold());
                    if (exceeded && open == null) {
                        if (!durationSatisfied(policy, fingerprint, now)) continue;
                        active.put(fingerprint, record(policy, metric, target, sample, fingerprint));
                        pendingSince.remove(fingerprint);
                    } else if (!exceeded) {
                        pendingSince.remove(fingerprint);
                        if (open != null) {
                            resolve(open, policy, sample);
                            active.remove(fingerprint);
                        }
                    }
                    // exceeded && open != null: 계속 초과 — 기존 행 유지(중복 억제)
                }
            }
        }
    }

    static final String SCOPE_WHAT = "역할 스코프 조회";

    static String thanosWhat(AlarmMetric metric) {
        return "Thanos 조회(" + metric.name() + ")";
    }

    /** durationSeconds 가 0 이면 즉시. 아니면 처음 초과 시각부터 지속 시간이 지났을 때만 true */
    private boolean durationSatisfied(AlarmPolicy policy, String fingerprint, Instant now) {
        if (policy.getDurationSeconds() <= 0) return true;
        Instant since = pendingSince.computeIfAbsent(fingerprint, k -> now);
        return !now.isBefore(since.plus(Duration.ofSeconds(policy.getDurationSeconds())));
    }

    /** 한 메트릭의 미해소 정책 알람을 fingerprint 로 색인. 지문 없는 옛 행(초안 평가기)은 대조 대상이 아니다 */
    private Map<String, AlarmHistory> activeByFingerprint(AlarmMetric metric) {
        Map<String, AlarmHistory> byFingerprint = new HashMap<>();
        for (AlarmHistory h : historyRepository.findBySourceAndMetricAndStatusNot(
                AlarmSource.POLICY, metric.name(), AlarmHistoryStatus.RESOLVED)) {
            if (h.getFingerprint() != null) byFingerprint.putIfAbsent(h.getFingerprint(), h);
        }
        return byFingerprint;
    }

    /** 정책×자원 지문 — SHA-256 hex(64자). 정책 id 가 아직 없으면(미저장) 이름으로 대신한다 */
    static String fingerprint(AlarmPolicy policy, AlarmMetric metric, String resourceId) {
        String key = "POLICY|" + (policy.getId() != null ? policy.getId() : policy.getName()) + "|" + metric.name() + "|" + resourceId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
        }
    }

    /** 활성 정책을 카탈로그 메트릭별로 묶는다. 해석되지 않는 metric 은 경고 후 제외(원장 검증을 거친 값이라 정상이면 없다) */
    static Map<AlarmMetric, List<AlarmPolicy>> groupByMetric(List<AlarmPolicy> policies) {
        Map<AlarmMetric, List<AlarmPolicy>> byMetric = new LinkedHashMap<>();
        for (AlarmPolicy policy : policies) {
            AlarmMetric.resolve(policy.getMetric()).ifPresentOrElse(
                    m -> byMetric.computeIfAbsent(m, k -> new ArrayList<>()).add(policy),
                    () -> log.warn("알람 정책 스킵 — 카탈로그에 없는 메트릭: policy={}, metric={}", policy.getName(), policy.getMetric()));
        }
        return byMetric;
    }

    /** 메트릭 하나의 전체 자원 현재값 — Prometheus 즉시 쿼리 응답(data.result[].metric/value)을 Sample 로 */
    private List<Sample> querySamples(AlarmMetric metric) {
        Map<String, Object> response = vmClient.query(metric.promql(GROUP_BY, null, settings.rateWindowPromql()));
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

    /** alarm_history 원장에 정책 스냅샷으로 기록하고 발생 이벤트를 발행 — docs/09 C.1·B.4.6 */
    private AlarmHistory record(AlarmPolicy policy, AlarmMetric metric, Target target, Sample sample, String fingerprint) {
        String criterion = policy.criterion();
        AlarmHistory.Builder builder = AlarmHistory.builder(AlarmSource.POLICY, policy.getLevel(), criterion)
                .policy(policy.getId(), policy.getName())
                .role(policy.getRoleName())
                .resource(target.resourceType(), target.resourceId(), target.name())
                .metric(metric.name())
                .observed(sample.value(), metric.unit())
                .fingerprint(fingerprint);
        if (target.nsId() != null) builder.namespace(target.nsId());
        else builder.mdc(target.mdcId());
        AlarmHistory alarm = historyRepository.save(builder.build());
        eventPublisher.publishEvent(AlarmStateChangedEvent.firing(alarm, sample.value()));

        log.info("알람 발생: policy={}, resource={}, value={}", policy.getName(), alarm.getResourceId(), sample.value());
        return alarm;
    }

    /** 임계값 안으로 돌아온 미해소 행을 RESOLVED 로 닫고 해소 이벤트를 발행 */
    private void resolve(AlarmHistory open, AlarmPolicy policy, Sample sample) {
        open.resolve(OffsetDateTime.now());
        AlarmHistory saved = historyRepository.save(open);
        eventPublisher.publishEvent(AlarmStateChangedEvent.resolved(saved, sample.value()));

        log.info("알람 해소: policy={}, resource={}, value={}", policy.getName(), open.getResourceId(), sample.value());
    }

    /** 정책 대상 자원 색인 — 호스트는 instance 라벨, VM 은 TB id 로 표본을 대조 */
    record PolicyTargets(Map<String, Target> byInstance, Map<String, Target> byVmId) {
        static PolicyTargets of(List<Target> targets) {
            Map<String, Target> byInstance = new HashMap<>();
            Map<String, Target> byVmId = new HashMap<>();
            for (Target t : targets) {
                if (t.nsId() != null) byVmId.put(t.resourceId(), t);
                else if (t.instance() != null) byInstance.put(t.instance(), t);
            }
            return new PolicyTargets(byInstance, byVmId);
        }

        Target match(Sample sample) {
            return sample.isTumblebugVm() ? byVmId.get(sample.vmResourceId()) : byInstance.get(sample.label("instance"));
        }
    }

    /** 자원 하나의 현재값과 식별 라벨 */
    record Sample(Map<String, String> labels, double value) {
        String label(String name) {
            String v = labels.get(name);
            return v == null || v.isBlank() ? null : v;
        }

        /** tb_ns·tb_node 라벨이 있으면 Tumblebug VM, 없으면 물리 호스트 */
        boolean isTumblebugVm() {
            return label("tb_ns") != null && label("tb_node") != null;
        }

        /** VM 자원 id — {@code TB:{ns}/{infra}/{node}} */
        String vmResourceId() {
            return "TB:" + label("tb_ns") + "/" + label("tb_infra") + "/" + label("tb_node");
        }
    }
}
