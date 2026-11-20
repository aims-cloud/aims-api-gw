package com.innogrid.aims.scheduler.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * 노드 배치 중지 원장 — 운영자가 새 배치를 받지 않게 한 노드.
 *
 * <p>배치 제약은 자원 · 사용률 · 열 · 전력 · 링크를 보지만, 운영자가 노드 한 대를 일부러 비워 두려는 뜻(점검 · 장애 의심 ·
 * 실험용 확보)을 넣을 자리는 없었다. 이 표가 그 자리다. 행이 있는 노드는 다음 배치부터 후보에서 빠지고, 지우면 다시 후보가 된다.
 * 이미 배치되거나 실행 중인 워크로드는 건드리지 않는다.</p>
 *
 * <p>노드 식별자의 <b>존재는 검증하지 않는다</b>(MDC 전력 예산 원장과 같은 판단). 검증하려면 원장 CRUD가 메트릭 저장소를
 * 불러야 한다. 어느 노드에도 걸리지 않는 오타 행은 아무 판정에도 쓰이지 않는다. 다른 담당의 자산 원장 점검 상태는 읽지 않는다.</p>
 */
@Entity
@Table(name = "node_placement_holds",
       uniqueConstraints = @UniqueConstraint(name = "uq_node_placement_holds_node", columnNames = "node_id"))
public class NodePlacementHold {

    public static final int NODE_ID_MAX = 100;
    public static final int REASON_MAX = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 노드 식별자 — 노드 목록({@code /nodes})의 {@code nodeId}와 같은 문자열. 대소문자를 구분한다. 등록 뒤에는 바꾸지 않는다. */
    @Column(name = "node_id", nullable = false, length = NODE_ID_MAX)
    private String nodeId;

    /** 중지 사유. 배치 이력의 제외 사유 문장에 그대로 실린다. */
    @Column(length = REASON_MAX)
    private String reason;

    // ── 감사 (다른 원장과 같은 4필드) ─────────────────────────
    @Column(length = 100)
    private String createdBy;

    @Column(length = 100)
    private String createdByName;

    @Column(length = 100)
    private String updatedBy;

    @Column(length = 100)
    private String updatedByName;

    /** 등록 시각 — 곧 배치 중지 시작 시각이다. 사유를 고쳐도 그대로다. */
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() { this.updatedAt = OffsetDateTime.now(); }

    protected NodePlacementHold() {}

    public NodePlacementHold(String nodeId) {
        this.nodeId = nodeId;
    }

    public void setCreatedBy(String email, String name) { this.createdBy = email; this.createdByName = name; }
    public void setUpdatedBy(String email, String name) { this.updatedBy = email; this.updatedByName = name; }

    public Long getId() { return id; }
    public String getNodeId() { return nodeId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getCreatedBy() { return createdBy; }
    public String getCreatedByName() { return createdByName; }
    public String getUpdatedBy() { return updatedBy; }
    public String getUpdatedByName() { return updatedByName; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
