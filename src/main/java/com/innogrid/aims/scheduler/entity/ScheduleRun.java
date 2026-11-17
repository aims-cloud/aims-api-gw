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

    // ── 클러스터 스냅샷 ─────────────────────────────────────────
    //
    // 전부 래퍼 타입이고 nullable이다. 원시 타입으로 두면 "재지 않았다"와 "0을 쟀다"가 구분되지 않고,
    // 행이 이미 있는 표에 NOT NULL 컬럼을 더하려다 스키마 갱신이 실패한다.
    //
    // 컬럼 이름을 명시하는 이유는 카멜→스네이크 변환이 마지막 글자를 보지 않아 끝이 대문자인 이름이
    // 뭉개지기 때문이다(2026-09-02 배포에서 mdcA → mdca로 실측). 여기서는 앞질러 막는다.

    /** 스냅샷 기록 상태. 이 PR 이전 행은 null이다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "snapshot_status", length = 20)
    private SnapshotStatus snapshotStatus;

    @Column(name = "snapshot_node_count")
    private Integer snapshotNodeCount;

    /** 메트릭을 못 얻어 후보에서 빠진 노드 수 — 제약으로 빠진 수가 아니다. */
    @Column(name = "snapshot_excluded_count")
    private Integer snapshotExcludedCount;

    /** 전력을 실측한 노드 수. 0이면 아래 전력 칸들이 전부 근거 없음이라는 뜻이다. */
    @Column(name = "snapshot_measured_nodes")
    private Integer snapshotMeasuredNodes;

    /** 실측 전력 합(W). 배치를 얹어도 다시 재지 않으므로 전·후가 없다. */
    @Column(name = "snapshot_measured_power_watts")
    private Double snapshotMeasuredPowerWatts;

    @Column(name = "snapshot_active_nodes_before")
    private Integer snapshotActiveNodesBefore;

    @Column(name = "snapshot_active_nodes_after")
    private Integer snapshotActiveNodesAfter;

    @Column(name = "snapshot_reclaimable_watts_before")
    private Double snapshotReclaimableWattsBefore;

    @Column(name = "snapshot_reclaimable_watts_after")
    private Double snapshotReclaimableWattsAfter;

    @Column(name = "snapshot_avg_temperature_before")
    private Double snapshotAvgTemperatureBefore;

    @Column(name = "snapshot_avg_temperature_after")
    private Double snapshotAvgTemperatureAfter;

    /**
     * 이 회차의 숫자를 정의한 설정 노브의 지문.
     *
     * <p>가동 판정 문턱·유휴 비율·유휴 역산 스위치·열관성 계수는 정책이 아니라 설정에서 온다. 값이 바뀌면
     * 그 뒤 회차는 같은 열에 <b>다른 정의</b>의 숫자를 적게 되므로, 지문이 다른 구간을 한 축으로 묶어
     * 집계하면 안 된다는 것을 이 칸으로 알린다.</p>
     */
    @Column(name = "snapshot_knobs_digest", length = 12)
    private String snapshotKnobsDigest;

    /**
     * 클러스터 스냅샷이 어디까지 기록됐는가.
     *
     * <p>빈 칸의 <b>까닭</b>을 남기는 값이다. 재지 못한 회차를 한 값으로 뭉뚱그리면 평범한 회차와 장애
     * 회차가 같은 표시를 달아, 어느 쪽이 쌓이고 있는지 이 표만으로 알 수 없게 된다.</p>
     */
    public enum SnapshotStatus {
        /** 전·후가 모두 찍혔다. */
        OK,
        /**
         * 전만 찍혔다 — 후 칸은 비어 있다.
         *
         * <p>전을 적어 저장한 뒤 후를 적기 전에 프로세스가 내려간 회차다. 배포가 곧 재시작이라 실제로
         * 생긴다. 배치가 예외로 실패한 회차는 여기 들지 않는다 — 그쪽은 후까지 적고 {@link #OK}로 끝난다.</p>
         */
        BEFORE_ONLY,
        /** 배치할 워크로드가 없어 재기 전에 끝났다. 클러스터는 멀쩡했고 잴 수도 있었다 — 평범한 회차다. */
        NO_TARGET,
        /** 노드를 한 대도 모아 오지 못했다. 메트릭 저장소가 아프거나 노드가 전부 내려간 상태다 — 장애다. */
        NO_NODES,
        /** 재는 도중 오류가 났다. 실행 자체는 이 값과 무관하게 끝난다. */
        FAILED
    }

    /** 실행 전 스냅샷을 적는다. 후를 적기 전까지 상태는 "전만"이다. */
    public void recordSnapshotBefore(String knobsDigest,
                                     Integer nodeCount, Integer excludedCount, Integer measuredNodes,
                                     Double measuredPowerWatts,
                                     Integer activeNodes, Double reclaimableWatts, Double avgTemperature) {
        this.snapshotKnobsDigest = knobsDigest;
        this.snapshotNodeCount = nodeCount;
        this.snapshotExcludedCount = excludedCount;
        this.snapshotMeasuredNodes = measuredNodes;
        this.snapshotMeasuredPowerWatts = measuredPowerWatts;
        this.snapshotActiveNodesBefore = activeNodes;
        this.snapshotReclaimableWattsBefore = reclaimableWatts;
        this.snapshotAvgTemperatureBefore = avgTemperature;
        this.snapshotStatus = SnapshotStatus.BEFORE_ONLY;
    }

    /** 실행 후 스냅샷을 적는다. 전이 없으면 후만 적지 않는다 — 짝이 없는 차는 뜻이 없다. */
    public void recordSnapshotAfter(Integer activeNodes, Double reclaimableWatts, Double avgTemperature) {
        if (snapshotStatus != SnapshotStatus.BEFORE_ONLY) return;
        this.snapshotActiveNodesAfter = activeNodes;
        this.snapshotReclaimableWattsAfter = reclaimableWatts;
        this.snapshotAvgTemperatureAfter = avgTemperature;
        this.snapshotStatus = SnapshotStatus.OK;
    }

    /** 배치할 워크로드가 없어 재기 전에 끝났다. */
    public void markSnapshotNoTarget() {
        this.snapshotStatus = SnapshotStatus.NO_TARGET;
    }

    /** 노드를 한 대도 모아 오지 못했다. */
    public void markSnapshotNoNodes() {
        this.snapshotStatus = SnapshotStatus.NO_NODES;
    }

    /** 재는 도중 오류가 났다. 이미 적은 칸은 비워 실행 이력이 반쪽 값을 들지 않게 한다. */
    public void markSnapshotFailed() {
        this.snapshotKnobsDigest = null;
        this.snapshotNodeCount = null;
        this.snapshotExcludedCount = null;
        this.snapshotMeasuredNodes = null;
        this.snapshotMeasuredPowerWatts = null;
        this.snapshotActiveNodesBefore = null;
        this.snapshotActiveNodesAfter = null;
        this.snapshotReclaimableWattsBefore = null;
        this.snapshotReclaimableWattsAfter = null;
        this.snapshotAvgTemperatureBefore = null;
        this.snapshotAvgTemperatureAfter = null;
        this.snapshotStatus = SnapshotStatus.FAILED;
    }

    public SnapshotStatus getSnapshotStatus() { return snapshotStatus; }
    public String getSnapshotKnobsDigest() { return snapshotKnobsDigest; }
    public Integer getSnapshotNodeCount() { return snapshotNodeCount; }
    public Integer getSnapshotExcludedCount() { return snapshotExcludedCount; }
    public Integer getSnapshotMeasuredNodes() { return snapshotMeasuredNodes; }
    public Double getSnapshotMeasuredPowerWatts() { return snapshotMeasuredPowerWatts; }
    public Integer getSnapshotActiveNodesBefore() { return snapshotActiveNodesBefore; }
    public Integer getSnapshotActiveNodesAfter() { return snapshotActiveNodesAfter; }
    public Double getSnapshotReclaimableWattsBefore() { return snapshotReclaimableWattsBefore; }
    public Double getSnapshotReclaimableWattsAfter() { return snapshotReclaimableWattsAfter; }
    public Double getSnapshotAvgTemperatureBefore() { return snapshotAvgTemperatureBefore; }
    public Double getSnapshotAvgTemperatureAfter() { return snapshotAvgTemperatureAfter; }

    protected ScheduleRun() {}

    public ScheduleRun(Long scheduleId, String scheduleKey, TriggerType triggerType, String triggeredBy) {
        this.scheduleId = scheduleId;
        this.scheduleKey = scheduleKey;
        this.triggerType = triggerType;
        this.triggeredBy = triggeredBy;
        this.startedAt = OffsetDateTime.now();
    }

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
        AUTO,       // cron에 따른 자동 실행
        MANUAL      // 사용자가 즉시 실행을 누름
    }

    public enum RunStatus {
        RUNNING, COMPLETED, FAILED
    }
}
