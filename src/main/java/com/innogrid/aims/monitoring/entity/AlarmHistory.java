package com.innogrid.aims.monitoring.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

@Entity
@Table(name = "alarm_history")
public class AlarmHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    private AlarmRule rule;

    @Column(nullable = false, length = 50)
    private String mdcId;

    @Column(nullable = false, length = 100)
    private String instance;

    @Column(name = "metric_value")
    private double value;

    @Column(nullable = false, length = 20)
    private String severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AlarmStatus status = AlarmStatus.OPEN;

    private OffsetDateTime triggeredAt;
    private OffsetDateTime resolvedAt;

    @PrePersist
    void onCreate() { this.triggeredAt = OffsetDateTime.now(); }

    protected AlarmHistory() {}

    public AlarmHistory(AlarmRule rule, String mdcId, String instance,
                        double value, String severity) {
        this.rule = rule;
        this.mdcId = mdcId;
        this.instance = instance;
        this.value = value;
        this.severity = severity;
    }

    public Long getId() { return id; }
    public AlarmRule getRule() { return rule; }
    public String getMdcId() { return mdcId; }
    public String getInstance() { return instance; }
    public double getValue() { return value; }
    public String getSeverity() { return severity; }
    public AlarmStatus getStatus() { return status; }
    public OffsetDateTime getTriggeredAt() { return triggeredAt; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }

    public void setStatus(AlarmStatus status) {
        this.status = status;
        if (status == AlarmStatus.RESOLVED) {
            this.resolvedAt = OffsetDateTime.now();
        }
    }

    public enum AlarmStatus {
        OPEN, ACKNOWLEDGED, RESOLVED
    }
}
