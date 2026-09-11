package com.innogrid.aims.monitoring.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmChannel;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmConditionOperator;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyType;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmTargetScope;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmTargetType;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleAreaListItemResponse.AreaKind;
import com.innogrid.aims.monitoring.service.alarm.AlarmMetric;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * 알람 정책 원장 (docs/09 A.5.3 {@code alarm_policies} + {@code alarm_policy_targets} + {@code alarm_policy_recipients}).
 *
 * <ul>
 *   <li>적용 범위: 역할({@code roleName}, 필수) → 자원 영역({@code areaId} = mdcId 또는 {@code TB:{nsId}}, 없으면 역할 전체)
 *       → 자원 유형(node|vm|gpu) → 대상 범위(ALL = 범위 내 전체 자원, INDIVIDUAL = {@code targetResourceIds}).
 *       대상 자원 id 는 자원 탐색기와 같은 값 — 호스트는 {@code assets.id}, Tumblebug VM 은 {@code TB:{ns}/{infra}/{node}}.</li>
 *   <li>조건: {@link AlarmMetric} 코드 + 연산자 + 임계값 + 레벨. 기준 문자열({@code CPU Used > 80%})은 저장하지 않고
 *       {@link #criterion()} 으로 만든다(이력에는 발생 시점 스냅샷으로 복사).</li>
 *   <li>전송: 조건 지속 시간(초, 0 = 즉시), 채널(EMAIL/SMS, CSV 저장), 수신자(개인 = users.id, 조직 = users.organization 문자열).</li>
 *   <li>{@code createdBy} 는 생성자 이메일(포털 로그 userId·확인자와 같은 체계). 표시명은 조회 시 users 로 채운다.</li>
 * </ul>
 * 대상·수신자는 element collection 으로 정책과 함께 저장·삭제된다.
 */
@Entity
@Table(name = "alarm_policies", indexes = {
        @Index(name = "idx_alarm_policies_role", columnList = "role_name"),
        @Index(name = "idx_alarm_policies_enabled_type", columnList = "enabled, type")
})
public class AlarmPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private AlarmPolicyType type = AlarmPolicyType.METRIC;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "role_name", nullable = false, length = 50)
    private String roleName;

    @Column(name = "area_id", length = 120)
    private String areaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "area_kind", length = 16)
    private AreaKind areaKind;

    @Column(name = "resource_type", nullable = false, length = 20)
    private String resourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_scope", nullable = false, length = 16)
    private AlarmTargetScope targetScope;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "alarm_policy_targets", joinColumns = @JoinColumn(name = "policy_id"),
            indexes = @Index(name = "idx_alarm_policy_targets_resource", columnList = "resource_id"))
    @Column(name = "resource_id", nullable = false, length = 200)
    private Set<String> targetResourceIds = new LinkedHashSet<>();

    @Column(name = "metric", nullable = false, length = 40)
    private String metric;

    @Enumerated(EnumType.STRING)
    @Column(name = "operator", nullable = false, length = 8)
    private AlarmConditionOperator operator;

    @Column(name = "threshold", nullable = false)
    private double threshold;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 16)
    private AlarmLevel level;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    /** EMAIL,SMS 처럼 CSV. 화면 표기는 {@link #getChannels()} */
    @Column(name = "channels", nullable = false, length = 64)
    private String channels;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "alarm_policy_recipients", joinColumns = @JoinColumn(name = "policy_id"))
    private List<RecipientRef> recipients = new ArrayList<>();

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** 수신자 1건 — 개인(users.id) 또는 조직(users.organization 문자열) */
    @Embeddable
    public record RecipientRef(
            @Enumerated(EnumType.STRING) @Column(name = "target_type", nullable = false, length = 16) AlarmTargetType targetType,
            @Column(name = "target_id", nullable = false, length = 100) String targetId) {}

    protected AlarmPolicy() {}

    public AlarmPolicy(String name, String roleName, String areaId, String resourceType,
                       AlarmTargetScope targetScope, Set<String> targetResourceIds,
                       AlarmMetric metric, AlarmConditionOperator operator, double threshold, AlarmLevel level,
                       int durationSeconds, List<AlarmChannel> channels, List<RecipientRef> recipients,
                       String description, String createdBy) {
        this.createdBy = createdBy;
        apply(name, roleName, areaId, resourceType, targetScope, targetResourceIds, metric, operator, threshold, level,
                durationSeconds, channels, recipients, description);
    }

    /** 생성·편집 공용 — 검증이 끝난 값만 받는다 */
    public void apply(String name, String roleName, String areaId, String resourceType,
                      AlarmTargetScope targetScope, Set<String> targetResourceIds,
                      AlarmMetric metric, AlarmConditionOperator operator, double threshold, AlarmLevel level,
                      int durationSeconds, List<AlarmChannel> channels, List<RecipientRef> recipients,
                      String description) {
        this.name = name;
        this.roleName = roleName;
        this.areaId = areaId;
        this.areaKind = areaKindOf(areaId);
        this.resourceType = resourceType;
        this.targetScope = targetScope;
        this.targetResourceIds = targetScope == AlarmTargetScope.INDIVIDUAL && targetResourceIds != null
                ? new LinkedHashSet<>(targetResourceIds) : new LinkedHashSet<>();
        this.metric = metric.name();
        this.operator = operator;
        this.threshold = threshold;
        this.level = level;
        this.durationSeconds = durationSeconds;
        this.channels = channels.stream().map(Enum::name).distinct().collect(Collectors.joining(","));
        this.recipients = new ArrayList<>(recipients);
        this.description = description;
    }

    /** 복제 — 같은 설정, 새 이름·생성자. id·시각은 새로 부여된다 */
    public AlarmPolicy copy(String newName, String createdBy) {
        return new AlarmPolicy(newName, roleName, areaId, resourceType, targetScope, targetResourceIds,
                alarmMetric(), operator, threshold, level, durationSeconds, getChannels(), recipients, description, createdBy);
    }

    /** {@code TB:} 접두면 Namespace, 그 외는 MDC, 없으면 null */
    public static AreaKind areaKindOf(String areaId) {
        if (areaId == null || areaId.isBlank()) return null;
        return areaId.startsWith("TB:") ? AreaKind.NAMESPACE : AreaKind.MDC;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public AlarmMetric alarmMetric() {
        return AlarmMetric.valueOf(metric);
    }

    /** 기준 문자열 — {@code CPU Used > 80%} */
    public String criterion() {
        return alarmMetric().criterion(operator.symbol(), threshold);
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Long getId() { return id; }
    public AlarmPolicyType getType() { return type; }
    public String getName() { return name; }
    public String getRoleName() { return roleName; }
    public String getAreaId() { return areaId; }
    public AreaKind getAreaKind() { return areaKind; }
    public String getResourceType() { return resourceType; }
    public AlarmTargetScope getTargetScope() { return targetScope; }
    public Set<String> getTargetResourceIds() { return targetResourceIds; }
    public String getMetric() { return metric; }
    public AlarmConditionOperator getOperator() { return operator; }
    public double getThreshold() { return threshold; }
    public AlarmLevel getLevel() { return level; }
    public int getDurationSeconds() { return durationSeconds; }
    public List<AlarmChannel> getChannels() {
        if (channels == null || channels.isBlank()) return List.of();
        return Arrays.stream(channels.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(AlarmChannel::valueOf).toList();
    }
    public List<RecipientRef> getRecipients() { return recipients; }
    public String getDescription() { return description; }
    public boolean isEnabled() { return enabled; }
    public String getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
