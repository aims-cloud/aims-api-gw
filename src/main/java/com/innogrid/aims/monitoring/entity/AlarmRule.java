package com.innogrid.aims.monitoring.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

@Entity
@Table(name = "alarm_rules")
public class AlarmRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 50)
    private String metricName;

    @Column(nullable = false, length = 10)
    private String condition;

    private double threshold;

    @Column(nullable = false, length = 20)
    private String severity;

    @Column(length = 50)
    private String notifyChannel;

    private boolean enabled = true;

    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() { this.createdAt = OffsetDateTime.now(); }

    protected AlarmRule() {}

    public AlarmRule(String name, String metricName, String condition,
                     double threshold, String severity, String notifyChannel) {
        this.name = name;
        this.metricName = metricName;
        this.condition = condition;
        this.threshold = threshold;
        this.severity = severity;
        this.notifyChannel = notifyChannel;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getMetricName() { return metricName; }
    public String getCondition() { return condition; }
    public double getThreshold() { return threshold; }
    public String getSeverity() { return severity; }
    public String getNotifyChannel() { return notifyChannel; }
    public boolean isEnabled() { return enabled; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setName(String name) { this.name = name; }
    public void setMetricName(String metricName) { this.metricName = metricName; }
    public void setCondition(String condition) { this.condition = condition; }
    public void setThreshold(double threshold) { this.threshold = threshold; }
    public void setSeverity(String severity) { this.severity = severity; }
    public void setNotifyChannel(String notifyChannel) { this.notifyChannel = notifyChannel; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
