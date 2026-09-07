package com.innogrid.aims.scheduler.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * 스케줄 실행 1회의 기록.
 *
 * <p>자동 실행이 대상 0건으로 끝났을 때는 이 행을 만들지 않는다. 5분 주기 스케줄
 * 하나가 90일이면 "대상 없음" 행만 25,920개를 남기기 때문이다. 대신 스케줄의
 * 최근 실행 스냅샷만 갱신해 목록·상세에서는 확인할 수 있게 한다. 사람이 직접 누른
 * 수동 실행은 결과를 봐야 하므로 대상이 없어도 항상 기록한다.</p>
 */
@Entity
@Table(name = "schedule_runs",
       indexes = @Index(name = "idx_schedule_runs_schedule_id", columnList = "scheduleId, startedAt"))
public class ScheduleRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 대상 스케줄({@code schedules.id}). */
    @Column(nullable = false)
    private Long scheduleId;

    /** 화면 표시용. 스케줄이 지워져도 이력만으로 읽을 수 있게 복사해 둔다. */
    @Column(length = 50)
    private String scheduleKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TriggerType triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RunStatus status = RunStatus.RUNNING;

    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;

    @Column(length = 500)
    private String message;

    // ── 집계 ───────────────────────────────────────────────────
    private int targetCount;
    private int successCount;
    private int failedCount;

    /** 상한에 걸려 이번 실행에서 처리하지 못한 대상이 남았는지. */
    private boolean truncated;

    // ── 실행 시점 스냅샷 ────────────────────────────────────────
    @Column(length = 30)
    private String engineId;

    @Column(length = 100)
    private String policyName;

    /** 실행 주체. 자동 실행은 SYSTEM. */
    @Column(length = 100)
    private String triggeredBy;

    protected ScheduleRun() {}

    public ScheduleRun(Long scheduleId, String scheduleKey, TriggerType triggerType, String triggeredBy) {
        this.scheduleId = scheduleId;
        this.scheduleKey = scheduleKey;
        this.triggerType = triggerType;
        this.triggeredBy = triggeredBy;
        this.startedAt = OffsetDateTime.now();
    }

    /** 실행 결과를 확정한다. */
    /** 실행 결과 메시지의 컬럼 길이. */
    public static final int MESSAGE_MAX = 500;

    /**
     * 실행을 마무리한다.
     *
     * <p>메시지를 <b>여기서 자른다.</b> 실패 사유에는 제외 노드 목록·첫 실패 사유가 붙어 500자를 넘길 수 있는데,
     * 그대로 저장하면 이 행 저장이 통째로 실패한다. 그러면 <b>성공한 실행이 실패로 뒤바뀌어 기록</b>되고,
     * 실패 경로에서는 실행 권한 해제까지 위태로워진다. 모든 마무리 경로가 이 메서드를 지나므로 여기가 길목이다.</p>
     */
    public void finish(RunStatus status, String message) {
        this.status = status;
        this.message = trim(message);
        this.finishedAt = OffsetDateTime.now();
    }

    /** 컬럼 길이에 맞춰 자른다. 잘렸다는 것이 보이도록 끝에 말줄임을 남긴다. */
    public static String trim(String message) {
        if (message == null || message.length() <= MESSAGE_MAX) return message;
        return message.substring(0, MESSAGE_MAX - 3) + "...";
    }

    public void recordCounts(int targetCount, int successCount, int failedCount, boolean truncated) {
        this.targetCount = targetCount;
        this.successCount = successCount;
        this.failedCount = failedCount;
        this.truncated = truncated;
    }

    public void recordContext(String engineId, String policyName) {
        this.engineId = engineId;
        this.policyName = policyName;
    }

    // Getters
    public Long getId() { return id; }
    public Long getScheduleId() { return scheduleId; }
    public String getScheduleKey() { return scheduleKey; }
    public TriggerType getTriggerType() { return triggerType; }
    public RunStatus getStatus() { return status; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public String getMessage() { return message; }
    public int getTargetCount() { return targetCount; }
    public int getSuccessCount() { return successCount; }
    public int getFailedCount() { return failedCount; }
    public boolean isTruncated() { return truncated; }
    public String getEngineId() { return engineId; }
    public String getPolicyName() { return policyName; }
    public String getTriggeredBy() { return triggeredBy; }

    /** 실행을 일으킨 주체. */
    public enum TriggerType {
        AUTO,       // cron 에 따른 자동 실행
        MANUAL      // 사용자가 즉시 실행을 누름
    }

    public enum RunStatus {
        RUNNING, COMPLETED, FAILED
    }
}
