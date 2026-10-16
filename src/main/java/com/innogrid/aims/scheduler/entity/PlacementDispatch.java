package com.innogrid.aims.scheduler.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * 배치 결정 한 건을 실행 계층으로 넘긴 기록 — 요청과 그 결과.
 *
 * <p>스케줄러는 어디에 둘지 <b>판단</b>만 하고, 워크로드를 실제로 올리는 <b>실행</b>은 실행 계층(1세부 API)이 한다.
 * 이 표는 그 사이의 왕복을 남긴다: 결정 직후 REQUESTED 행이 생기고, 디스패처가 넘긴 결과(ACCEPTED·REJECTED·UNAVAILABLE)로
 * 갱신되며, 실행 계층이 콜백으로 알려 주는 진행(RUNNING → COMPLETED / FAILED)이 뒤따른다.</p>
 *
 * <p>워크로드와는 FK 없이 식별자로만 연결한다. 워크로드 삭제 때 함께 지운다(서비스 규칙).
 * 우리 결정의 기록이므로 저장해도 원시 메트릭을 저장하지 않는 원칙에 맞는다.</p>
 */
@Entity
@Table(name = "placement_dispatches",
       uniqueConstraints = @UniqueConstraint(name = "uq_placement_dispatches_key", columnNames = "idempotency_key"),
       indexes = {
           @Index(name = "idx_placement_dispatches_workload", columnList = "workload_id"),
           @Index(name = "idx_placement_dispatches_status", columnList = "status")
       })
public class PlacementDispatch {

    /**
     * 디스패치 상태.
     *
     * <pre>
     * REQUESTED ─▶ ACCEPTED ─▶ RUNNING ─▶ COMPLETED
     *      │            │           └────▶ FAILED
     *      │            └──────────────────▶ FAILED   (실행 실패 콜백 · 또는 운영자의 예약 취소 — 비고가 "예약 취소 — " 로 시작)
     *   열린 상태(REQUESTED·ACCEPTED·RUNNING) ─▶ COMPLETED·FAILED   운영자의 상태 변경 — 그 결정의 기록을 워크로드와 같은 끝 상태로 마감(비고가 "운영자 상태 변경 — " 로 시작)
     *      ├─▶ REJECTED     실행 계층이 거부 (워크로드는 FAILED + 배정 해제)
     *      ├─▶ UNAVAILABLE  실행 계층에 닿지 못함 (워크로드는 FAILED + 배정 해제)
     *      └─▶ TIMED_OUT    원격 모드에서 응답 시간 상한 초과 — <b>record 모드에서는 도달하지 않는다</b>
     *                       (판정하는 코드가 아직 없다. 값은 계약·CHECK 제약에 있으므로 유지)
     * </pre>
     * Postgres 는 이 값 목록으로 CHECK 제약을 만든다 — 값을 더할 때는 DDL 스크립트도 고친다.
     */
    public enum DispatchStatus {
        REQUESTED, ACCEPTED, RUNNING, COMPLETED, FAILED, REJECTED, UNAVAILABLE, TIMED_OUT;

        /** 실행 계층이 콜백으로 보고할 수 있는 상태. */
        public boolean reportable() {
            return this == RUNNING || this == COMPLETED || this == FAILED;
        }

        /** 더 진행할 수 없는 끝 상태. */
        public boolean terminal() {
            return this == COMPLETED || this == FAILED || this == REJECTED || this == UNAVAILABLE || this == TIMED_OUT;
        }

        /** 콜백 전이표. 같은 상태로의 재보고는 여기서 false 이고, 서비스가 멱등(무변경 200)으로 다룬다. */
        public boolean canReport(DispatchStatus target) {
            return switch (this) {
                case ACCEPTED -> target == RUNNING || target == FAILED;
                case RUNNING -> target == COMPLETED || target == FAILED;
                default -> false;
            };
        }
    }

    /** 컬럼 길이 — 콜백 입력 검증과 비고 잘라내기가 같은 값을 쓴다. */
    public static final int EXTERNAL_REF_MAX = 200;
    public static final int MESSAGE_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workload_id", nullable = false)
    private Long workloadId;

    @Column(length = 100)
    private String workloadName;

    private Long scheduleRunId;

    @Column(length = 50)
    private String mdcId;

    @Column(length = 100)
    private String nodeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DispatchStatus status = DispatchStatus.REQUESTED;

    /** {@code workloadId:scheduledAt(epoch ms)} — 같은 결정을 두 번 넘기지 않게 한다. */
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    /** 실행 계층이 돌려준 식별자. 기록형 디스패처는 비워 둔다. */
    @Column(length = EXTERNAL_REF_MAX)
    private String externalRef;

    @Column(length = MESSAGE_MAX)
    private String message;

    @Column(nullable = false)
    private OffsetDateTime requestedAt;

    private OffsetDateTime respondedAt;

    @Column(length = 100)
    private String createdBy;

    protected PlacementDispatch() {}

    public PlacementDispatch(Workload workload, Long scheduleRunId, String createdBy) {
        this.workloadId = workload.getId();
        this.workloadName = workload.getName();
        this.scheduleRunId = scheduleRunId;
        this.mdcId = workload.getAssignedMdcId();
        this.nodeId = workload.getAssignedNodeId();
        this.idempotencyKey = keyOf(workload);
        this.requestedAt = OffsetDateTime.now();
        this.createdBy = createdBy;
    }

    /** 멱등키 — 워크로드 식별자와 배정 시각(ms). 같은 결정은 같은 키다. */
    public static String keyOf(Workload workload) {
        long at = workload.getScheduledAt() == null ? 0 : workload.getScheduledAt().toInstant().toEpochMilli();
        return workload.getId() + ":" + at;
    }

    /** 디스패처가 수락했다. */
    public void accept(String externalRef, String message) {
        this.status = DispatchStatus.ACCEPTED;
        this.externalRef = externalRef;
        this.message = message;
        this.respondedAt = OffsetDateTime.now();
    }

    /** 디스패처가 거부했거나 닿지 못했다. */
    public void close(DispatchStatus outcome, String message) {
        this.status = outcome;
        this.message = message;
        this.respondedAt = OffsetDateTime.now();
    }

    /**
     * 운영자가 배치 예약을 취소해 이 결정을 끝낸다 — 상태는 기존 값 {@code FAILED} 로 마감하고 비고로 구별한다.
     *
     * <p>새 상태 값을 두지 않는 이유: 상태 컬럼에 값 목록 CHECK 제약이 있고 {@code ddl-auto=update} 는 기존 제약을 고치지
     * 않아, 새 값은 배포 서버에서 UPDATE 실패가 된다. FAILED 는 이미 끝 상태라 늦게 온 RUNNING·COMPLETED 콜백은 전이표에서
     * 거부(409)되고, 늦은 FAILED 재보고는 무변경으로 끝난다 — 원하는 동작이 값 추가 없이 성립한다.</p>
     */
    public void cancel(String text) {
        this.status = DispatchStatus.FAILED;
        // 표지를 앞에 둔다 — 기존 비고가 길어 잘려도 "예약 취소" 구별 수단은 남아야 한다(note 는 뒤에 붙여 표지가 잘릴 수 있다)
        String merged = (this.message == null || this.message.isBlank()) ? text : text + " / " + this.message;
        this.message = merged.length() > MESSAGE_MAX ? merged.substring(0, MESSAGE_MAX) : merged;
        this.respondedAt = OffsetDateTime.now();
    }

    /**
     * 운영자가 상태 변경 API 로 워크로드를 끝 상태로 보내 이 결정을 끝낸다 — 상태는 기존 값({@code COMPLETED} 또는
     * {@code FAILED})으로 마감하고 비고 앞에 표지를 둔다. 새 상태 값을 두지 않는 이유는 {@link #cancel} 과 같다.
     * 끝 상태가 되면 같은 상태의 재보고는 무변경으로 끝나고 그 밖의 늦은 콜백은 전이표에서 거부(409)된다.
     */
    public void finishByOperator(DispatchStatus outcome, String text) {
        this.status = outcome;
        String merged = (this.message == null || this.message.isBlank()) ? text : text + " / " + this.message;
        this.message = merged.length() > MESSAGE_MAX ? merged.substring(0, MESSAGE_MAX) : merged;
        this.respondedAt = OffsetDateTime.now();
    }

    /**
     * 실행 계층의 콜백을 반영한다. 허용되지 않는 전이는 false. 같은 상태로의 재보고는 true 를 돌려주되 아무것도 바꾸지
     * 않는다(멱등) — 호출자가 "바뀐 것이 없다"를 알아야 하면 {@link #getStatus()} 를 비교한다.
     */
    public boolean report(DispatchStatus target, String externalRef, String message) {
        if (target == null || !target.reportable()) return false;
        if (target == this.status) return true;
        if (!this.status.canReport(target)) return false;
        this.status = target;
        if (externalRef != null) this.externalRef = externalRef;
        if (message != null) this.message = message;
        this.respondedAt = OffsetDateTime.now();
        return true;
    }

    /** 상태는 그대로 두고 비고만 덧붙인다(워크로드 전이 불일치 같은 사실을 남길 때). */
    public void note(String text) {
        if (text == null) return;
        String merged = (this.message == null || this.message.isBlank()) ? text : this.message + " / " + text;
        this.message = merged.length() > MESSAGE_MAX ? merged.substring(0, MESSAGE_MAX) : merged;
    }

    public Long getId() { return id; }
    public Long getWorkloadId() { return workloadId; }
    public String getWorkloadName() { return workloadName; }
    public Long getScheduleRunId() { return scheduleRunId; }
    public String getMdcId() { return mdcId; }
    public String getNodeId() { return nodeId; }
    public DispatchStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getExternalRef() { return externalRef; }
    public String getMessage() { return message; }
    public OffsetDateTime getRequestedAt() { return requestedAt; }
    public OffsetDateTime getRespondedAt() { return respondedAt; }
    public String getCreatedBy() { return createdBy; }
}
