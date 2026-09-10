package com.innogrid.aims.bmc;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 마지막으로 성공한 Redfish 하드웨어 조회 결과(스냅샷).
 * 배포 환경에서 BMC 에 실시간으로 닿지 못할 때 프론트에 내려줄 폴백 데이터로 쓴다.
 * 실시간 조회가 성공할 때마다 upsert 되어 최신값을 유지한다. PK = BMC 엔드포인트 id.
 */
@Entity
@Table(name = "bmc_hardware_snapshots")
public class BmcHardwareSnapshot {

    @Id
    @Column(name = "bmc_endpoint_id", length = 50)
    private String bmcEndpointId;

    private String manufacturer;
    private String model;

    @Column(name = "serial_number")
    private String serialNumber;

    @Column(name = "host_name")
    private String hostName;

    @Column(name = "power_state", length = 20)
    private String powerState;

    @Column(length = 20)
    private String health;

    @Column(name = "cpu_model")
    private String cpuModel;

    @Column(name = "cpu_count")
    private Integer cpuCount;

    @Column(name = "core_count")
    private Integer coreCount;

    @Column(name = "thread_count")
    private Integer threadCount;

    @Column(name = "memory_gib")
    private Integer memoryGiB;

    /** 이 스냅샷을 수집(마지막 성공)한 시각. */
    @Column(name = "collected_at")
    private OffsetDateTime collectedAt;

    protected BmcHardwareSnapshot() {}

    public BmcHardwareSnapshot(String bmcEndpointId) {
        this.bmcEndpointId = bmcEndpointId;
    }

    /** 실시간 조회 결과로 스냅샷 내용을 갱신한다. */
    public void apply(RedfishSystem s, OffsetDateTime at) {
        this.manufacturer = s.manufacturer();
        this.model = s.model();
        this.serialNumber = s.serialNumber();
        this.hostName = s.hostName();
        this.powerState = s.powerState();
        this.health = s.health();
        this.cpuModel = s.cpuModel();
        this.cpuCount = s.cpuCount();
        this.coreCount = s.coreCount();
        this.threadCount = s.threadCount();
        this.memoryGiB = s.memoryGiB();
        this.collectedAt = at;
    }

    /** 저장된 스냅샷을 정규화 레코드로 되돌린다. */
    public RedfishSystem toRedfishSystem() {
        return new RedfishSystem(bmcEndpointId, manufacturer, model, serialNumber, hostName,
                powerState, health, cpuModel, cpuCount, coreCount, threadCount, memoryGiB);
    }

    public String getBmcEndpointId() { return bmcEndpointId; }
    public OffsetDateTime getCollectedAt() { return collectedAt; }
}
