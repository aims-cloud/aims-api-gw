package com.innogrid.aims.infra.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

@Entity
@Table(name = "racks")
public class Rack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String mdcId;

    @Column(nullable = false, length = 100)
    private String name;

    private int totalUnits = 42;
    private double powerCapacityKw;

    // ── 배치/필터 필드 (Figma 랙실장도 화면) ─────────────────────

    /** 분류 (예: 서버랙, 네트워크랙) */
    @Column(length = 50)
    private String category;

    /** 장소 (예: 본관 전산실) */
    @Column(length = 100)
    private String location;

    /** 층 (예: 3F) */
    @Column(name = "floor_no", length = 20)
    private String floorNo;

    /** 구역 (예: A구역) */
    @Column(name = "zone_name", length = 50)
    private String zoneName;

    /** 열 (예: 1열) */
    @Column(name = "row_no", length = 20)
    private String rowNo;

    /** 위치 (열 내 순번, 예: 3) */
    @Column(name = "position_no", length = 20)
    private String positionNo;

    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() { this.createdAt = OffsetDateTime.now(); }

    protected Rack() {}

    public Rack(String mdcId, String name, int totalUnits, double powerCapacityKw) {
        this.mdcId = mdcId;
        this.name = name;
        this.totalUnits = totalUnits;
        this.powerCapacityKw = powerCapacityKw;
    }

    public Long getId() { return id; }
    public String getMdcId() { return mdcId; }
    public String getName() { return name; }
    public int getTotalUnits() { return totalUnits; }
    public double getPowerCapacityKw() { return powerCapacityKw; }
    public String getCategory() { return category; }
    public String getLocation() { return location; }
    public String getFloorNo() { return floorNo; }
    public String getZoneName() { return zoneName; }
    public String getRowNo() { return rowNo; }
    public String getPositionNo() { return positionNo; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setName(String name) { this.name = name; }
    public void setTotalUnits(int totalUnits) { this.totalUnits = totalUnits; }
    public void setPowerCapacityKw(double powerCapacityKw) { this.powerCapacityKw = powerCapacityKw; }
    public void setCategory(String category) { this.category = category; }
    public void setLocation(String location) { this.location = location; }
    public void setFloorNo(String floorNo) { this.floorNo = floorNo; }
    public void setZoneName(String zoneName) { this.zoneName = zoneName; }
    public void setRowNo(String rowNo) { this.rowNo = rowNo; }
    public void setPositionNo(String positionNo) { this.positionNo = positionNo; }
}
