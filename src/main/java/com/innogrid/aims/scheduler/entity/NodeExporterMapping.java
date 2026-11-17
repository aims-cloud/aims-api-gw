package com.innogrid.aims.scheduler.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * 익스포터 대응 원장 — 노드 한 대의 DCGM·IPMI 시리즈가 어떤 {@code instance}로 저장소에 있는지.
 *
 * <p>프로메테우스의 {@code instance} 라벨은 스크레이프 주소 그대로라 익스포터마다 다르다. 노드 익스포터는
 * {@code host:9100}, DCGM은 {@code host:9400}, IPMI는 {@code ipmi-exporter:9290} 같은 중앙 주소다(배포 서버
 * 실측). 수집기는 노드 익스포터의 값을 키로 GPU 개수를 찾고 전력·온도를 질의해 왔으므로 실장비에서는 GPU 개수가
 * 항상 0이고 전력·온도가 항상 미계측이었다. 이 표는 그 세 값을 운영자가 잇는 자리다.</p>
 *
 * <p>정격 전력 원장과 같은 유형이다 — 센서가 알려 주지 않고 우리가 입력한다. 두 칸 모두 비워 둘 수 있고, 비어 있는
 * 칸은 수집기가 호스트명 일치로 대체하며, 그것도 안 되면 노드 익스포터 instance를 그대로 쓴다(이 표가 생기기 전과
 * 같은 동작). 값의 존재는 검증하지 않는다 — 검증하려면 원장 CRUD가 저장소를 불러야 한다.</p>
 *
 * <p>열거 열이 없다. 열거 열은 처음 만들 때의 값 목록으로 CHECK가 굳어 값을 더할 때마다 스크립트가 필요하다.</p>
 */
@Entity
@Table(name = "node_exporter_mappings",
       uniqueConstraints = @UniqueConstraint(name = "uq_node_exporter_mappings_node", columnNames = "node_id"))
public class NodeExporterMapping {

    public static final int INSTANCE_MAX = 100;
    public static final int DESCRIPTION_MAX = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 노드 식별자 — 노드 익스포터의 {@code instance} 라벨 그대로({@code seoul-01:9100}). 다른 원장과 같은 키다. */
    @Column(name = "node_id", nullable = false, length = INSTANCE_MAX)
    private String nodeId;

    /** 이 노드의 DCGM 시리즈 {@code instance}. 비우면 호스트명 일치로 대체한다. */
    @Column(name = "dcgm_instance", length = INSTANCE_MAX)
    private String dcgmInstance;

    /** 이 노드의 IPMI 시리즈 {@code instance}. 중앙 익스포터라면 호스트명이 달라 이 칸이 유일한 길이다. */
    @Column(name = "ipmi_instance", length = INSTANCE_MAX)
    private String ipmiInstance;

    @Column(length = DESCRIPTION_MAX)
    private String description;

    // ── 감사 (다른 원장과 같은 4필드) ──────────────────────────
    @Column(length = 100)
    private String createdBy;

    @Column(length = 100)
    private String createdByName;

    @Column(length = 100)
    private String updatedBy;

    @Column(length = 100)
    private String updatedByName;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() { this.updatedAt = OffsetDateTime.now(); }

    protected NodeExporterMapping() {}

    public NodeExporterMapping(String nodeId) {
        this.nodeId = nodeId;
    }

    public void setCreatedBy(String email, String name) { this.createdBy = email; this.createdByName = name; }
    public void setUpdatedBy(String email, String name) { this.updatedBy = email; this.updatedByName = name; }

    public Long getId() { return id; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getDcgmInstance() { return dcgmInstance; }
    public void setDcgmInstance(String dcgmInstance) { this.dcgmInstance = dcgmInstance; }
    public String getIpmiInstance() { return ipmiInstance; }
    public void setIpmiInstance(String ipmiInstance) { this.ipmiInstance = ipmiInstance; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCreatedBy() { return createdBy; }
    public String getCreatedByName() { return createdByName; }
    public String getUpdatedBy() { return updatedBy; }
    public String getUpdatedByName() { return updatedByName; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
