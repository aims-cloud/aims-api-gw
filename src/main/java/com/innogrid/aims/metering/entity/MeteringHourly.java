package com.innogrid.aims.metering.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

@Entity
@Table(name = "metering_hourly", indexes = {
        @Index(name = "idx_metering_time", columnList = "time"),
        @Index(name = "idx_metering_mdc_metric", columnList = "mdcId, metricName, time")
})
public class MeteringHourly {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private OffsetDateTime time;

    @Column(nullable = false, length = 50)
    private String mdcId;

    @Column(nullable = false, length = 100)
    private String resourceId;

    @Column(nullable = false, length = 50)
    private String metricName;

    private Double avgValue;
    private Double maxValue;
    private Double minValue;
    private Integer sampleCount;

    protected MeteringHourly() {}

    public MeteringHourly(OffsetDateTime time, String mdcId, String resourceId,
                          String metricName, Double avgValue, Double maxValue,
                          Double minValue, Integer sampleCount) {
        this.time = time;
        this.mdcId = mdcId;
        this.resourceId = resourceId;
        this.metricName = metricName;
        this.avgValue = avgValue;
        this.maxValue = maxValue;
        this.minValue = minValue;
        this.sampleCount = sampleCount;
    }

    public Long getId() { return id; }
    public OffsetDateTime getTime() { return time; }
    public String getMdcId() { return mdcId; }
    public String getResourceId() { return resourceId; }
    public String getMetricName() { return metricName; }
    public Double getAvgValue() { return avgValue; }
    public Double getMaxValue() { return maxValue; }
    public Double getMinValue() { return minValue; }
    public Integer getSampleCount() { return sampleCount; }
}
