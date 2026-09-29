package com.innogrid.aims.monitoring.entity;

import java.time.OffsetDateTime;
import com.innogrid.aims.infra.entity.Asset;
import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 노드 수집 에이전트(Alloy) 관리 원장 — 자산 1건당 1행(docs/11 §4.4, Confluence "노드 로그/트레이스 기능 API 흐름 및 수집 에이전트 관리 설계" §5).
 *
 * 지금은 "수집 대상 목록(log-targets.json)을 어디에 어떻게 쓰는가"({@link AgentTransport}, targetsPath)와 그 적용 상태만 쓴다.
 * SSH 접속 정보·설치 상태 컬럼은 에이전트 매니저 구현(접속 정보 수령 방식 확정 후)을 위해 미리 두었고 전부 nullable 이다.
 * ssh_secret 은 저장 시 암호화한다(구현 예정). 조회 응답에는 비밀값을 내리지 않는다.
 */
@Getter
@Entity
@Table(name = "agent_nodes")
public class AgentNode {

    @Id
    @Column(name = "asset_id")
    private Long assetId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "asset_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Asset asset;

    /** 목록 파일 전송 방식. NONE 이면 이 노드에는 목록을 밀어 넣지 않는다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "transport", nullable = false, length = 16)
    private AgentTransport transport = AgentTransport.NONE;

    /** 목록 파일 경로. LOCAL 은 백엔드가 직접 쓰는 로컬 경로, SSH 는 노드 안 경로 */
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

    public void configureTransport(AgentTransport transport, String targetsPath, String updatedBy) {
        this.transport = transport;
        this.targetsPath = targetsPath;
        this.updatedBy = updatedBy;
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
