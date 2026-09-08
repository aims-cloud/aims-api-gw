package com.innogrid.aims.infra.ipam.entity;

import jakarta.persistence.*;

/**
 * 네트워크 구역 ↔ OpenStack 네트워크 연결.
 * '인터페이스 관리'에서 선택한 배포영역/네트워크를 구역에 붙인다.
 */
@Entity
@Table(name = "network_zone_interfaces",
        uniqueConstraints = @UniqueConstraint(columnNames = {"zone_id", "openstack_network_id"}))
public class NetworkZoneInterface {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "zone_id", nullable = false)
    private Long zoneId;

    /** 배포영역 (토폴로지 deployZone). */
    @Column(length = 100)
    private String deployZone;

    @Column(name = "openstack_network_id", length = 100)
    private String openstackNetworkId;

    @Column(length = 200)
    private String networkName;

    /** 데이터센터 (표시용, 없으면 null). */
    @Column(length = 100)
    private String datacenter;

    protected NetworkZoneInterface() {}

    public NetworkZoneInterface(Long zoneId, String deployZone, String openstackNetworkId,
                                String networkName, String datacenter) {
        this.zoneId = zoneId;
        this.deployZone = deployZone;
        this.openstackNetworkId = openstackNetworkId;
        this.networkName = networkName;
        this.datacenter = datacenter;
    }

    public Long getId() { return id; }
    public Long getZoneId() { return zoneId; }
    public String getDeployZone() { return deployZone; }
    public String getOpenstackNetworkId() { return openstackNetworkId; }
    public String getNetworkName() { return networkName; }
    public String getDatacenter() { return datacenter; }
}
