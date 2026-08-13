package com.innogrid.aims.monitoring.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String mdcId;

    @Column(nullable = false, length = 20)
    private String severity;

    @Column(nullable = false, length = 100)
    private String source;

    @Column(nullable = false, length = 500)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status = EventStatus.OPEN;

    private OffsetDateTime time;

    @PrePersist
    void onCreate() { this.time = OffsetDateTime.now(); }

    protected Event() {}

    public Event(String mdcId, String severity, String source, String message) {
        this.mdcId = mdcId;
        this.severity = severity;
        this.source = source;
        this.message = message;
    }

    public Long getId() { return id; }
    public String getMdcId() { return mdcId; }
    public String getSeverity() { return severity; }
    public String getSource() { return source; }
    public String getMessage() { return message; }
    public EventStatus getStatus() { return status; }
    public OffsetDateTime getTime() { return time; }

    public void setStatus(EventStatus status) { this.status = status; }

    public enum EventStatus {
        OPEN, ACKNOWLEDGED, RESOLVED
    }
}
