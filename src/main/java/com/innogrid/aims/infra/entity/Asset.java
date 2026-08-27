package com.innogrid.aims.infra.entity;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import jakarta.persistence.*;

@Entity
@Table(name = "assets")
public class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String mdcId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AssetType type;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String manufacturer;

    @Column(length = 100)
    private String model;

    @Column(length = 100)
    private String serialNumber;

    @Column(length = 100)
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssetStatus status = AssetStatus.ACTIVE;

    private LocalDate purchaseDate;
    private OffsetDateTime createdAt;

    // ── 확장 필드 (Figma 물리 자산 화면) ─────────────────────────

    /** 대분류 (예: 하드웨어) */
    @Column(length = 50)
    private String categoryMajor;

    /** 중분류 (예: 서버) */
    @Column(length = 50)
    private String categoryMiddle;

    /** 소분류 (예: GPU 서버) */
    @Column(length = 50)
    private String categoryMinor;

    /** 세분류 */
    @Column(length = 50)
    private String categoryDetail;

    /** 장비위치 (장소/층/구역/열 표기) */
    @Column(length = 200)
    private String equipmentLocation;

    /** 랙위치 (랙 이름) */
    @Column(length = 100)
    private String rackLocation;

    /** 마운트포인트 (랙 내 U 위치, 예: U10) */
    @Column(length = 20)
    private String mountPoint;

    /** 단위크기 (U) */
    private Integer unitSize;

    /** 시스템유형 (예: x86, ARM, Storage) */
    @Column(length = 50)
    private String systemType;

    /** 데이터 출처 (SELF: 자체 등록, SEBU1: 1세부 수신) */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssetSource source = AssetSource.SELF;

    /** 출처 시스템에서의 자산 식별자 (1세부 데이터 동기화 upsert 키) */
    @Column(length = 100)
    private String externalId;

    @PrePersist
    void onCreate() { this.createdAt = OffsetDateTime.now(); }

    protected Asset() {}

    public Asset(String mdcId, AssetType type, String name) {
        this.mdcId = mdcId;
        this.type = type;
        this.name = name;
    }

    public Long getId() { return id; }
    public String getMdcId() { return mdcId; }
    public AssetType getType() { return type; }
    public String getName() { return name; }
    public String getManufacturer() { return manufacturer; }
    public String getModel() { return model; }
    public String getSerialNumber() { return serialNumber; }
    public String getLocation() { return location; }
    public AssetStatus getStatus() { return status; }
    public LocalDate getPurchaseDate() { return purchaseDate; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public String getCategoryMajor() { return categoryMajor; }
    public String getCategoryMiddle() { return categoryMiddle; }
    public String getCategoryMinor() { return categoryMinor; }
    public String getCategoryDetail() { return categoryDetail; }
    public String getEquipmentLocation() { return equipmentLocation; }
    public String getRackLocation() { return rackLocation; }
    public String getMountPoint() { return mountPoint; }
    public Integer getUnitSize() { return unitSize; }
    public String getSystemType() { return systemType; }
    public AssetSource getSource() { return source; }
    public String getExternalId() { return externalId; }

    public void setName(String name) { this.name = name; }
    public void setManufacturer(String manufacturer) { this.manufacturer = manufacturer; }
    public void setModel(String model) { this.model = model; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }
    public void setLocation(String location) { this.location = location; }
    public void setStatus(AssetStatus status) { this.status = status; }
    public void setPurchaseDate(LocalDate purchaseDate) { this.purchaseDate = purchaseDate; }
    public void setCategoryMajor(String categoryMajor) { this.categoryMajor = categoryMajor; }
    public void setCategoryMiddle(String categoryMiddle) { this.categoryMiddle = categoryMiddle; }
    public void setCategoryMinor(String categoryMinor) { this.categoryMinor = categoryMinor; }
    public void setCategoryDetail(String categoryDetail) { this.categoryDetail = categoryDetail; }
    public void setEquipmentLocation(String equipmentLocation) { this.equipmentLocation = equipmentLocation; }
    public void setRackLocation(String rackLocation) { this.rackLocation = rackLocation; }
    public void setMountPoint(String mountPoint) { this.mountPoint = mountPoint; }
    public void setUnitSize(Integer unitSize) { this.unitSize = unitSize; }
    public void setSystemType(String systemType) { this.systemType = systemType; }
    public void setSource(AssetSource source) { this.source = source; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public enum AssetType {
        SERVER, GPU_SERVER, SWITCH, SAN_SWITCH, STORAGE, UPS, PDU, OTHER
    }

    public enum AssetStatus {
        ACTIVE, INACTIVE, MAINTENANCE, DECOMMISSIONED
    }

    public enum AssetSource {
        SELF, SEBU1
    }
}
