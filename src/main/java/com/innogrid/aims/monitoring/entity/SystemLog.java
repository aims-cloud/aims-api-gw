package com.innogrid.aims.monitoring.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

@Entity
@Table(name = "system_logs")
public class SystemLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String mdcId;

    @Column(nullable = false, length = 100)
    private String source;

    @Column(nullable = false, length = 10)
    private String level;

    @Column(nullable = false, length = 2000)
    private String message;

    private OffsetDateTime time;

    @PrePersist
    void onCreate() { this.time = OffsetDateTime.now(); }

    protected SystemLog() {}

    public SystemLog(String mdcId, String source, String level, String message) {
        this.mdcId = mdcId;
        this.source = source;
        this.level = level;
        this.message = message;
    }

    public Long getId() { return id; }
    public String getMdcId() { return mdcId; }
    public String getSource() { return source; }
    public String getLevel() { return level; }
    public String getMessage() { return message; }
    public OffsetDateTime getTime() { return time; }
}
