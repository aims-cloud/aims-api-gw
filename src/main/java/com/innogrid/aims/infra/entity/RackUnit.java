package com.innogrid.aims.infra.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "rack_units")
public class RackUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rack_id", nullable = false)
    private Rack rack;

    @Column(nullable = false, length = 20)
    private String position;

    @Column(length = 100)
    private String assetName;

    @Column(length = 30)
    private String assetType;

    protected RackUnit() {}

    public RackUnit(Rack rack, String position, String assetName, String assetType) {
        this.rack = rack;
        this.position = position;
        this.assetName = assetName;
        this.assetType = assetType;
    }

    public Long getId() { return id; }
    public Rack getRack() { return rack; }
    public String getPosition() { return position; }
    public String getAssetName() { return assetName; }
    public String getAssetType() { return assetType; }

    public void setPosition(String position) { this.position = position; }
    public void setAssetType(String assetType) { this.assetType = assetType; }
}
