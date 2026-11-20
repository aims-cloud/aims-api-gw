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

    /**
     * BMC(Redfish) 엔드포인트 식별자. 하드웨어 상세조회가 가능한 장비만 값을 가진다.
     * {@code bmc.endpoints[].id}(application.yml)와 매칭되며, 비어 있으면 실시간 조회 대상이 아니다.
     */
    @Column(name = "bmc_endpoint_id", length = 50)
    private String bmcEndpointId;

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
    public String getBmcEndpointId() { return bmcEndpointId; }

    public void setPosition(String position) { this.position = position; }
    public void setAssetType(String assetType) { this.assetType = assetType; }
    public void setBmcEndpointId(String bmcEndpointId) { this.bmcEndpointId = bmcEndpointId; }
}
