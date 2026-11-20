package com.innogrid.aims.resource.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

@Entity
@Table(name = "catalog_items")
public class CatalogItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 300)
    private String description;

    private int cpu;
    private int memoryGb;
    private int diskGb;

    @Column(length = 50)
    private String os;

    @Column(length = 50)
    private String gpu;

    private int monthlyPrice;

    @Column(nullable = false, length = 30)
    private String category;

    private boolean active = true;
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() { this.createdAt = OffsetDateTime.now(); }

    protected CatalogItem() {}

    public CatalogItem(String name, String description, String category) {
        this.name = name;
        this.description = description;
        this.category = category;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getCpu() { return cpu; }
    public int getMemoryGb() { return memoryGb; }
    public int getDiskGb() { return diskGb; }
    public String getOs() { return os; }
    public String getGpu() { return gpu; }
    public int getMonthlyPrice() { return monthlyPrice; }
    public String getCategory() { return category; }
    public boolean isActive() { return active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setCpu(int cpu) { this.cpu = cpu; }
    public void setMemoryGb(int memoryGb) { this.memoryGb = memoryGb; }
    public void setDiskGb(int diskGb) { this.diskGb = diskGb; }
    public void setOs(String os) { this.os = os; }
    public void setGpu(String gpu) { this.gpu = gpu; }
    public void setMonthlyPrice(int monthlyPrice) { this.monthlyPrice = monthlyPrice; }
    public void setCategory(String category) { this.category = category; }
    public void setActive(boolean active) { this.active = active; }
}
