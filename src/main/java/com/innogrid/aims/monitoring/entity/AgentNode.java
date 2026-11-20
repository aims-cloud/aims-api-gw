package com.innogrid.aims.monitoring.entity;

import java.time.OffsetDateTime;
import com.innogrid.aims.infra.entity.Asset;
import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 수집 에이전트(Alloy) 관리 원장 — 물리 자산 1건 또는 Tumblebug VM 1건당 1행(docs/11 §4.4,
 * Confluence "노드 로그/트레이스 기능 API 흐름 및 수집 에이전트 관리 설계" §5). 소유자는 {@link #owner()}.
 *
 * "수집 대상 목록을 어떻게 전달하는가"({@link AgentTransport}, targetsPath)와 그 적용 상태, 그리고 VM 설치 상태를 쓴다.
 * SSH 접속 정보 컬럼은 2026-09-30 책임님 답변(노드에 접속하지 않음)으로 사용 계획이 없어졌고 nullable 로 남아 있다.
 */
@Getter
@Entity
@Table(name = "agent_nodes", uniqueConstraints =
        @UniqueConstraint(name = "uk_agent_nodes_vm", columnNames = {"tb_ns", "tb_infra", "tb_node"}))
public class AgentNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 물리 노드 에이전트의 자산(자산당 1행). VM 에이전트면 null */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Asset asset;

    /** VM 에이전트의 Tumblebug 키(VM 당 1행). 물리 노드 에이전트면 셋 다 null */
    @Column(name = "tb_ns", length = 200)
    private String tbNs;
    @Column(name = "tb_infra", length = 200)
    private String tbInfra;
    @Column(name = "tb_node", length = 200)
    private String tbNode;

    /** 목록 파일 전송 방식. NONE 이면 이 노드에는 목록을 밀어 넣지 않는다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "transport", nullable = false, length = 16)
    private AgentTransport transport = AgentTransport.NONE;

    /**
     * 목록 파일 경로. LOCAL 은 백엔드가 직접 쓰는 로컬 경로, SSH 는 노드 안 경로. PULL 은 쓰지 않는다(null) —
     * PULL 노드는 node_exporter 타겟 주소(instance)로 식별되어 목록 API 로 가져간다(deploy/sql/2026-10-06_agent_nodes.sql).
     */
    @Column(name = "targets_path", length = 500)
    private String targetsPath;

    // ── SSH 접속 정보 (에이전트 매니저 구현 시 사용, 지금은 비움) ──
    @Column(name = "ssh_host", length = 255)
    private String sshHost;
    @Column(name = "ssh_port")
    private Integer sshPort;
    @Column(name = "ssh_user", length = 100)
    private String sshUser;
    @Column(name = "ssh_secret", columnDefinition = "text")
    private String sshSecret;
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", length = 16)
    private AgentAuthType authType;
    @Column(name = "access_status", length = 16)
    private String accessStatus;
    @Column(name = "access_checked_at")
    private OffsetDateTime accessCheckedAt;

    // ── 설치 상태 ──
    @Enumerated(EnumType.STRING)
    @Column(name = "install_status", nullable = false, length = 16)
    private AgentInstallStatus installStatus = AgentInstallStatus.NOT_INSTALLED;
    @Column(name = "install_step", length = 50)
    private String installStep;
    @Column(name = "install_error", columnDefinition = "text")
    private String installError;
    @Column(name = "installed_at")
    private OffsetDateTime installedAt;
    @Column(name = "agent_version", length = 50)
    private String agentVersion;

    // ── 목록 적용 상태 ──
    /** 원장으로 계산한 목록의 해시. 적용 해시와 다르면 재적용 대상 */
    @Column(name = "targets_hash_desired", length = 64)
    private String targetsHashDesired;
    @Column(name = "targets_hash_applied", length = 64)
    private String targetsHashApplied;
    @Column(name = "targets_applied_at")
    private OffsetDateTime targetsAppliedAt;
    @Column(name = "targets_error", columnDefinition = "text")
    private String targetsError;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "updated_by", length = 200)
    private String updatedBy;

    protected AgentNode() {}

    public AgentNode(Asset asset, AgentTransport transport, String targetsPath) {
        this.asset = asset;
        this.transport = transport;
        this.targetsPath = targetsPath;
    }

    /** Tumblebug VM 에이전트 — 백엔드가 VM 에 접속하지 않으므로 목록은 VM 의 Alloy 가 가져간다(PULL) */
    public static AgentNode forVm(CollectOwner owner) {
        if (!owner.isVm()) {
            throw new IllegalArgumentException("VM 소유자가 아닙니다: " + owner.label());
        }
        AgentNode node = new AgentNode();
        node.tbNs = owner.tbNs();
        node.tbInfra = owner.tbInfra();
        node.tbNode = owner.tbNode();
        node.transport = AgentTransport.PULL;
        return node;
    }

    /** 물리 노드 에이전트의 자산 id. VM 에이전트면 null — 지연 로딩 프록시여도 id 는 초기화 없이 읽힌다 */
    public Long getAssetId() {
        return asset == null ? null : asset.getId();
    }

    /** 이 에이전트가 반영하는 원장의 소유자 */
    public CollectOwner owner() {
        return asset != null ? CollectOwner.asset(asset.getId()) : CollectOwner.vm(tbNs, tbInfra, tbNode);
    }

    public void configureTransport(AgentTransport transport, String targetsPath, String updatedBy) {
        this.transport = transport;
        this.targetsPath = targetsPath;
        this.updatedBy = updatedBy;
    }

    /** PULL 전송으로 바꾼다. 목록 파일 경로는 쓰지 않으므로 비운다 */
    public void configurePull(String updatedBy) {
        this.transport = AgentTransport.PULL;
        this.targetsPath = null;
        this.updatedBy = updatedBy;
    }

    /** 설치 진행 상태 — 단계 이름과 사유를 함께 남긴다. INSTALLED 면 설치 시각과 버전을 기록한다 */
    public void markInstall(AgentInstallStatus status, String step, String error, String version) {
        this.installStatus = status;
        this.installStep = step;
        this.installError = error;
        if (status == AgentInstallStatus.INSTALLED) {
            this.installedAt = OffsetDateTime.now();
            this.agentVersion = version;
        }
    }

    public void markDesired(String hash) {
        this.targetsHashDesired = hash;
    }

    public void markApplied(String hash, OffsetDateTime at) {
        this.targetsHashApplied = hash;
        this.targetsAppliedAt = at;
        this.targetsError = null;
    }

    public void markApplyFailed(String error) {
        this.targetsError = error;
    }

    /** 원하는 목록이 아직 노드에 반영되지 않았는가 */
    public boolean isTargetsPending() {
        return targetsHashDesired != null && !targetsHashDesired.equals(targetsHashApplied);
    }

    @PrePersist
    void created() { createdAt = updatedAt = OffsetDateTime.now(); }
    @PreUpdate
    void updated() { updatedAt = OffsetDateTime.now(); }
}
