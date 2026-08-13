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

    public void setName(String name) { this.name = name; }
    public void setManufacturer(String manufacturer) { this.manufacturer = manufacturer; }
    public void setModel(String model) { this.model = model; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }
    public void setLocation(String location) { this.location = location; }
    public void setStatus(AssetStatus status) { this.status = status; }
    public void setPurchaseDate(LocalDate purchaseDate) { this.purchaseDate = purchaseDate; }

    public enum AssetType {
        SERVER, GPU_SERVER, SWITCH, SAN_SWITCH, STORAGE, UPS, PDU, OTHER
    }

    public enum AssetStatus {
        ACTIVE, INACTIVE, MAINTENANCE, DECOMMISSIONED
    }
}
