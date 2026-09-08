package com.innogrid.aims.monitoring.entity;

import java.time.OffsetDateTime;

import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmHistoryStatus;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmSource;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleAreaListItemResponse.AreaKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * 알람 발생 이력 원장 — 테이블 {@code alarm_history} (docs/09 A.5.3, B.4.7, C.2).
 *
 * <ul>
 *   <li>정책·자원·기준·관측값은 발생 시점 <b>스냅샷</b>이다. 정책이 나중에 바뀌어도 이력은 불변이라 FK 대신 값을 복사한다
 *       ({@code policy_id} 는 조인용 약한 참조).</li>
 *   <li>자원 식별은 자원 탐색기와 같다 — 물리 호스트 {@code assets.id}, Tumblebug VM {@code TB:{ns}/{infra}/{node}}.</li>
 *   <li>{@code role_name} 은 역할 스코프, {@code area_id}·{@code area_kind} 는 트리의 자원 영역(MDC 는 {@code mdcId},
 *       Tumblebug Namespace 는 {@code TB:{nsId}}) — 역할 패널 자원 영역 표의 {@code areaId} 와 같은 값이다.
 *       내장 시스템 규칙(SYSTEM)은 역할이 null 이라 모든 사용자에게 보인다.</li>
 *   <li>{@code fingerprint} 는 외부(EXTERNAL) 알람 멱등 키이자 정책 알람의 규칙×자원 지문(평가기 중복 억제·해소 키), {@code inhibited} 는 상위(사이트·에이전트) 알람에 눌려
 *       전송이 억제된 행 표시다.</li>
 *   <li>초안의 {@code alarm_history}(rule_id FK·instance·metric_value) 를 같은 이름으로 재정의했다. 평가기와 구 API 는 이 엔티티를 쓴다.</li>
 * </ul>
 */
@Entity
@Table(name = "alarm_history", indexes = {
        @Index(name = "idx_alarm_history_occurred_at", columnList = "occurred_at"),
        @Index(name = "idx_alarm_history_role_occurred", columnList = "role_name, occurred_at"),
        @Index(name = "idx_alarm_history_resource", columnList = "resource_id, occurred_at"),
        @Index(name = "idx_alarm_history_policy", columnList = "policy_id, occurred_at"),
        @Index(name = "idx_alarm_history_status", columnList = "status"),
        @Index(name = "idx_alarm_history_fingerprint", columnList = "fingerprint")
})
public class AlarmHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private AlarmSource source;

    @Column(name = "policy_id")
    private Long policyId;

    @Column(name = "policy_name", length = 100)
    private String policyName;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 16)
    private AlarmLevel level;

    /** 대상 자원 유형 — node | vm | gpu (프론트 RESOURCE_TYPE_OPTIONS 코드값) */
    @Column(name = "resource_type", length = 20)
    private String resourceType;

    @Column(name = "resource_id", length = 200)
    private String resourceId;

    @Column(name = "resource_name", length = 200)
    private String resourceName;

    @Column(name = "role_name", length = 50)
    private String roleName;

    /** 자원 영역 식별자 — MDC: mdcId, Namespace: TB:{nsId} (역할 패널 areaId 와 동일) */
    @Column(name = "area_id", length = 120)
    private String areaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "area_kind", length = 16)
    private AreaKind areaKind;

    /** 메트릭 카탈로그 코드 (예: CPU_USED_PERCENT) */
    @Column(name = "metric", length = 50)
    private String metric;

    /** 기준 문자열 스냅샷 (예: "CPU Used > 80%") */
    @Column(name = "criterion", nullable = false, length = 200)
    private String criterion;

    @Column(name = "observed_value")
    private Double observedValue;

    /** 관측값 단위 (%, GB, °C …) — 화면 표기는 value+unit */
    @Column(name = "unit", length = 10)
    private String unit;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AlarmHistoryStatus status = AlarmHistoryStatus.OPEN;

    @Column(name = "acknowledged_at")
    private OffsetDateTime acknowledgedAt;

    @Column(name = "acknowledged_by", length = 64)
    private String acknowledgedBy;

    @Column(name = "ack_content", length = 1000)
    private String ackContent;

    @Column(name = "fingerprint", length = 64)
    private String fingerprint;

    @Column(name = "inhibited", nullable = false)
    private boolean inhibited;

    @Column(name = "labels_json", columnDefinition = "text")
    private String labelsJson;

    /** 전송 이력 수 — 전송 원장이 갱신하는 비정규화 값(목록 컬럼용) */
    @Column(name = "delivery_count", nullable = false)
    private int deliveryCount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (occurredAt == null) {
            occurredAt = createdAt;
        }
    }

    protected AlarmHistory() {}

    private AlarmHistory(Builder b) {
        this.source = b.source;
        this.policyId = b.policyId;
        this.policyName = b.policyName;
        this.level = b.level;
        this.resourceType = b.resourceType;
        this.resourceId = b.resourceId;
        this.resourceName = b.resourceName;
        this.roleName = b.roleName;
        this.areaId = b.areaId;
        this.areaKind = b.areaKind;
        this.metric = b.metric;
        this.criterion = b.criterion;
        this.observedValue = b.observedValue;
        this.unit = b.unit;
        this.occurredAt = b.occurredAt;
        this.fingerprint = b.fingerprint;
        this.inhibited = b.inhibited;
        this.labelsJson = b.labelsJson;
    }

    public static Builder builder(AlarmSource source, AlarmLevel level, String criterion) {
        return new Builder(source, level, criterion);
    }

    /** 확인 처리 — 확인자·시각·내용을 기록하고 상태를 ACKNOWLEDGED 로. RESOLVED 된 알람도 확인 기록은 남길 수 있다 */
    public void acknowledge(String userId, String content, OffsetDateTime at) {
        this.acknowledgedBy = userId;
        this.ackContent = content;
        this.acknowledgedAt = at;
        if (this.status == AlarmHistoryStatus.OPEN) {
            this.status = AlarmHistoryStatus.ACKNOWLEDGED;
        }
    }

    /** 해소 — 조건이 더 이상 참이 아님 */
    public void resolve(OffsetDateTime at) {
        this.resolvedAt = at;
        this.status = AlarmHistoryStatus.RESOLVED;
    }

    public void incrementDeliveryCount(int by) {
        this.deliveryCount += by;
    }

    public boolean isAcknowledged() {
        return acknowledgedAt != null;
    }

    public Long getId() { return id; }
    public AlarmSource getSource() { return source; }
    public Long getPolicyId() { return policyId; }
    public String getPolicyName() { return policyName; }
    public AlarmLevel getLevel() { return level; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getResourceName() { return resourceName; }
    public String getRoleName() { return roleName; }
    public String getAreaId() { return areaId; }
    public AreaKind getAreaKind() { return areaKind; }
    public String getMetric() { return metric; }
    public String getCriterion() { return criterion; }
    public Double getObservedValue() { return observedValue; }
    public String getUnit() { return unit; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public AlarmHistoryStatus getStatus() { return status; }
    public OffsetDateTime getAcknowledgedAt() { return acknowledgedAt; }
    public String getAcknowledgedBy() { return acknowledgedBy; }
    public String getAckContent() { return ackContent; }
    public String getFingerprint() { return fingerprint; }
    public boolean isInhibited() { return inhibited; }
    public String getLabelsJson() { return labelsJson; }
    public int getDeliveryCount() { return deliveryCount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public static final class Builder {
        private final AlarmSource source;
        private final AlarmLevel level;
        private final String criterion;
        private Long policyId;
        private String policyName;
        private String resourceType;
        private String resourceId;
        private String resourceName;
        private String roleName;
        private String areaId;
        private AreaKind areaKind;
        private String metric;
        private Double observedValue;
        private String unit;
        private OffsetDateTime occurredAt = OffsetDateTime.now();
        private String fingerprint;
        private boolean inhibited;
        private String labelsJson;

        private Builder(AlarmSource source, AlarmLevel level, String criterion) {
            this.source = source;
            this.level = level;
            this.criterion = criterion;
        }

        public Builder policy(Long policyId, String policyName) { this.policyId = policyId; this.policyName = policyName; return this; }
        public Builder resource(String type, String id, String name) { this.resourceType = type; this.resourceId = id; this.resourceName = name; return this; }
        public Builder role(String roleName) { this.roleName = roleName; return this; }
        /** 물리 자원 — MDC 영역 */
        public Builder mdc(String mdcId) { this.areaId = mdcId; this.areaKind = mdcId == null ? null : AreaKind.MDC; return this; }
        /** Tumblebug VM — Namespace 영역 (areaId = TB:{nsId}) */
        public Builder namespace(String nsId) { this.areaId = nsId == null ? null : "TB:" + nsId; this.areaKind = nsId == null ? null : AreaKind.NAMESPACE; return this; }
        public Builder metric(String metric) { this.metric = metric; return this; }
        public Builder observed(Double value, String unit) { this.observedValue = value; this.unit = unit; return this; }
        public Builder occurredAt(OffsetDateTime v) { this.occurredAt = v; return this; }
        public Builder fingerprint(String v) { this.fingerprint = v; return this; }
        public Builder inhibited(boolean v) { this.inhibited = v; return this; }
        public Builder labelsJson(String v) { this.labelsJson = v; return this; }

        public AlarmHistory build() {
            return new AlarmHistory(this);
        }
    }
}
