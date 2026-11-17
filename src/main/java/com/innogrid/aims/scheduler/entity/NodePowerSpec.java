package com.innogrid.aims.scheduler.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * 노드 전력 사양 원장 — 노드 한 대의 정격 전력(W).
 *
 * <p>IPMI는 <b>지금 쓰는 전력</b>만 알려 주고 <b>정격</b>은 알려 주지 않는다. 그래서 지금까지 모든 노드를 설정
 * 기본값(500W) 하나로 봤고, 그 값이 배치 제외 판정·전력 점수·회수 가능 전력·통합 절감 추정 네 곳에 그대로 쓰였다.
 * 이 표는 운영자가 아는 값(장비 사양서)을 넣어 그 가정을 실제 값으로 바꾸는 자리다.</p>
 *
 * <p>MDC 링크 원장과 같은 유형이다 — 컨소시엄이 주지 않는 값이고 우리가 입력하므로 저장해도 원시 메트릭을 저장하지 않는 원칙에 맞는다.
 * 노드 식별자의 <b>존재는 검증하지 않는다</b>: 검증하려면 원장 CRUD가 메트릭 저장소를 불러야 한다. 어느 노드에도
 * 걸리지 않는 오타 항목은 아무 판정에도 쓰이지 않아 무해하다.</p>
 */
@Entity
@Table(name = "node_power_specs",
       uniqueConstraints = @UniqueConstraint(name = "uq_node_power_specs_node", columnNames = "node_id"))
public class NodePowerSpec {

    /** 값의 출처. {@code MEASURED}는 실측 연동을 위해 값만 예약했다 — 채우는 경로는 아직 없다. */
    public enum PowerSpecSource { MANUAL, SPEC_SHEET, MEASURED }

    public static final int NODE_ID_MAX = 100;
    public static final int DESCRIPTION_MAX = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 노드 식별자 — Prometheus {@code instance} 라벨과 같은 문자열({@code seoul-01:9100}). */
    @Column(name = "node_id", nullable = false, length = NODE_ID_MAX)
    private String nodeId;

    /**
     * 정격 전력(W). 0보다 크고 유한해야 한다.
     *
     * <p><b>크기 상한은 두지 않는다.</b> 실데이터가 없는 지금 상한은 추측일 뿐이라 정당한 장비를 막을 수 있다 —
     * GPU 서버 한 대가 10kW 급이고 랙이나 MDC 컨테이너를 한 노드로 등록하면 수십 kW다. 이 리포의 원장 원칙대로
     * "저장하면 확실히 틀린 것"만 거부한다. 무한대는 여유율을 {@code (∞−실측)/∞ = NaN}으로 만들어 후보 정렬과
     * 직렬화를 깨뜨리므로 그것만 막는다.</p>
     */
    @Column(name = "capacity_watts", nullable = false)
    private double capacityWatts;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PowerSpecSource source = PowerSpecSource.MANUAL;

    @Column(length = DESCRIPTION_MAX)
    private String description;

    // ── 감사 (링크 원장과 같은 4필드) ──────────────────────────
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

    protected NodePowerSpec() {}

    public NodePowerSpec(String nodeId, double capacityWatts) {
        this.nodeId = nodeId;
        this.capacityWatts = capacityWatts;
    }

    public void setCreatedBy(String email, String name) { this.createdBy = email; this.createdByName = name; }
    public void setUpdatedBy(String email, String name) { this.updatedBy = email; this.updatedByName = name; }

    public Long getId() { return id; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public double getCapacityWatts() { return capacityWatts; }
    public void setCapacityWatts(double capacityWatts) { this.capacityWatts = capacityWatts; }
    public PowerSpecSource getSource() { return source; }
    public void setSource(PowerSpecSource source) { this.source = source; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCreatedBy() { return createdBy; }
    public String getCreatedByName() { return createdByName; }
    public String getUpdatedBy() { return updatedBy; }
    public String getUpdatedByName() { return updatedByName; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
