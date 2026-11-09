package com.innogrid.aims.scheduler.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.*;

/**
 * 스케줄 정의 — 언제 어떤 리소스 종류의 워크로드를 배치할지 정한다.
 *
 * <p>화면의 7필드(스케줄 ID·이름·리소스 종류·실행 주기·자동 실행·사용 여부·설명)에
 * 명세서가 요구하는 엔진·정책·전력/온도 파라미터를 선택 항목으로 더했다. 화면에 없는
 * 필드는 전부 null 을 허용하고 실행 시점에 기본값으로 해석하므로, 화면 폼만으로 만든
 * 스케줄도 그대로 동작한다.</p>
 *
 * <p>시각 필드는 전부 UTC {@code OffsetDateTime} 이다. cron 만 한국 시간으로 해석하며
 * 그 근거는 {@link com.innogrid.aims.scheduler.service.CronSupport} 에 적어 두었다.</p>
 *
 * <p><b>편집은 바뀐 칸만 UPDATE 한다({@code @DynamicUpdate}).</b> 최근 실행 네 칸(실행 상태·시작·종료·메시지)은
 * 실행 계층의 전용 UPDATE({@link com.innogrid.aims.scheduler.repository.ScheduleRepository#acquireForRun}·
 * {@link com.innogrid.aims.scheduler.repository.ScheduleRepository#recordRunResult})만 쓰고 편집은 손대지 않는데,
 * 모든 칸을 쓰는 UPDATE 면 편집이 읽은 시점 값으로 그 칸을 되돌려 실행 권한이 풀리거나(편집 읽기 → 실행 시작 → 편집 저장)
 * 끝난 실행이 실행 중으로 되살아난다(실행 시작 → 편집 읽기 → 실행 종료 → 편집 저장). 이 보호는 편집이 같은 트랜잭션에서
 * 읽은 관리 엔티티에 세터로 반영하는 지금 구조를 전제한다 — 분리된 사본을 merge 하면 DB 와 다른 칸이 전부 더티가 되어
 * 다시 열린다. 스키마와는 무관하다.</p>
 */
@Entity
@DynamicUpdate
@Table(name = "schedules",
       indexes = @Index(name = "idx_schedules_next_run_at", columnList = "nextRunAt"))
public class Schedule {

    /** 스케줄 ID 로 허용하는 형식. 화면에서 사용자가 직접 입력한다. */
    public static final String ID_PATTERN = "^[A-Za-z0-9][A-Za-z0-9_.-]{0,49}$";

    /**
     * 문자열 컬럼의 길이 상한. 서비스가 이 값으로 400 을 만든다.
     *
     * <p>컬럼 정의가 이 상수를 직접 쓴다({@code @Column(length = NAME_MAX)}) — 한쪽만 바뀌는 일이 구조적으로 불가능하다.
     * 같은 패키지의 다른 원장 엔티티와 같은 형태다. 검증이 없으면 INSERT 가 컬럼 길이에서 실패하고, 그 예외를 잡는
     * 곳이 없어 클라이언트는 400 이 아니라 403(본문 없음)을 받는다.</p>
     */
    public static final int NAME_MAX = 100;
    public static final int CRON_MAX = 120;
    public static final int DESCRIPTION_MAX = 300;
    public static final int ENGINE_MAX = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 사용자가 정하는 업무 키. 생성 후 바꿀 수 없다. */
    @Column(nullable = false, unique = true, length = 50)
    private String scheduleId;

    @Column(nullable = false, length = NAME_MAX)
    private String name;

    /** 배치 대상 리소스 종류. null 이면 VM 으로 해석한다. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Workload.ResourceType resourceType;

    /** Quartz 표기 cron. 사용자가 입력한 원문 그대로 보관해 화면에 되돌려 준다. */
    @Column(nullable = false, length = CRON_MAX)
    private String cronExpression;

    /** cron 에 따라 자동으로 실행할지. 꺼도 즉시 실행은 된다. */
    @Column(nullable = false)
    private boolean autoRun = false;

    /** 사용 여부. 끄면 자동·즉시 실행이 모두 막힌다. */
    @Column(nullable = false)
    private boolean enabled = true;

    @Column(length = DESCRIPTION_MAX)
    private String description;

    // ── 명세 확장 (화면에 없음, 전부 선택) ──────────────────────
    /** 사용할 엔진. null 이면 정책의 기본 엔진, 그것도 없으면 weighted. */
    @Column(length = ENGINE_MAX)
    private String engine;

    /** 사용할 배치 정책. null 이면 리소스 종류별 활성 정책. */
    private Long policyId;

    private Double maxPowerBudgetWatts;
    private Double maxTemperatureCelsius;

    /**
     * 대상 워크로드를 배치하는 순서. null 이면 {@link PlacementOrder#PRIORITY_THEN_AGE}(현행).
     *
     * <p>크기 순은 빈 패킹의 first-fit-decreasing 에서 가져온 선택지이며, 기본 엔진에서의 조각화 효과는 배치 전략 비교로 합성 판에서만 쟀다(docs/14).
     * 우선순위는 어느 쪽이든 첫 기준이라 공정성과 충돌하지 않는다 — 크기 우선은 <b>같은 우선순위 안에서만</b> 큰 것부터다.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private PlacementOrder placementOrder;

    /** 배치 순서 선택지. */
    public enum PlacementOrder {
        /** 우선순위 높은 것부터, 같으면 먼저 등록된 것부터(현행). */
        PRIORITY_THEN_AGE,
        /** 우선순위 높은 것부터, 같으면 요구 CPU → 요구 메모리가 큰 것부터, 그래도 같으면 먼저 등록된 것부터. */
        PRIORITY_THEN_SIZE;

        public static PlacementOrder orDefault(PlacementOrder v) {
            return v == null ? PRIORITY_THEN_AGE : v;
        }
    }

    // ── 최근 실행 스냅샷 ────────────────────────────────────────
    // 목록 화면이 실행 이력을 조인하지 않고도 최근 상태를 보여줄 수 있게 비정규화했다.
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ScheduleRun.RunStatus lastRunStatus;

    private OffsetDateTime lastRunAt;
    private OffsetDateTime lastRunFinishedAt;

    @Column(length = 500)
    private String lastRunMessage;

    /** 다음 자동 실행 예정 시각(UTC). 사용 여부를 켜거나 cron 을 바꾸면 반드시 다시 계산한다. */
    private OffsetDateTime nextRunAt;

    // ── 감사 ───────────────────────────────────────────────────
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

    protected Schedule() {}

    public Schedule(String scheduleId, String name, String cronExpression) {
        this.scheduleId = scheduleId;
        this.name = name;
        this.cronExpression = cronExpression;
    }

    // ── 상태 판단 ───────────────────────────────────────────────

    /** 자동 실행 대상인지. 사용 여부와 자동 실행이 모두 켜져 있어야 한다. */
    public boolean isAutoRunnable() {
        return enabled && autoRun;
    }

    /** 값이 없으면 VM 으로 해석한 리소스 종류. */
    public Workload.ResourceType resolveResourceType() {
        return Workload.ResourceType.orDefault(resourceType);
    }

    /** 값이 없으면 현행(우선순위 → 나이)으로 해석한 배치 순서. */
    public PlacementOrder resolvePlacementOrder() {
        return PlacementOrder.orDefault(placementOrder);
    }

    /** 실행 결과를 최근 실행 스냅샷에 반영한다. */
    public void recordRun(ScheduleRun.RunStatus status, OffsetDateTime startedAt,
                          OffsetDateTime finishedAt, String message) {
        this.lastRunStatus = status;
        this.lastRunAt = startedAt;
        this.lastRunFinishedAt = finishedAt;
        this.lastRunMessage = message;
    }

    // Getters
    public Long getId() { return id; }
    public String getScheduleId() { return scheduleId; }
    public String getName() { return name; }
    public Workload.ResourceType getResourceType() { return resourceType; }
    public String getCronExpression() { return cronExpression; }
    public boolean isAutoRun() { return autoRun; }
    public boolean isEnabled() { return enabled; }
    public String getDescription() { return description; }
    public PlacementOrder getPlacementOrder() { return placementOrder; }
    public String getEngine() { return engine; }
    public Long getPolicyId() { return policyId; }
    public Double getMaxPowerBudgetWatts() { return maxPowerBudgetWatts; }
    public Double getMaxTemperatureCelsius() { return maxTemperatureCelsius; }
    public ScheduleRun.RunStatus getLastRunStatus() { return lastRunStatus; }
    public OffsetDateTime getLastRunAt() { return lastRunAt; }
    public OffsetDateTime getLastRunFinishedAt() { return lastRunFinishedAt; }
    public String getLastRunMessage() { return lastRunMessage; }
    public OffsetDateTime getNextRunAt() { return nextRunAt; }
    public String getCreatedBy() { return createdBy; }
    public String getCreatedByName() { return createdByName; }
    public String getUpdatedBy() { return updatedBy; }
    public String getUpdatedByName() { return updatedByName; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    // Setters — scheduleId 는 불변이라 세터를 두지 않는다
    public void setName(String name) { this.name = name; }
    public void setResourceType(Workload.ResourceType resourceType) { this.resourceType = resourceType; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
    public void setAutoRun(boolean autoRun) { this.autoRun = autoRun; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setDescription(String description) { this.description = description; }
    public void setEngine(String engine) { this.engine = engine; }
    public void setPolicyId(Long policyId) { this.policyId = policyId; }
    public void setMaxPowerBudgetWatts(Double v) { this.maxPowerBudgetWatts = v; }
    public void setMaxTemperatureCelsius(Double v) { this.maxTemperatureCelsius = v; }
    public void setPlacementOrder(PlacementOrder v) { this.placementOrder = v; }
    public void setNextRunAt(OffsetDateTime nextRunAt) { this.nextRunAt = nextRunAt; }
    public void setCreatedBy(String email, String name) {
        this.createdBy = email;
        this.createdByName = name;
    }
    public void setUpdatedBy(String email, String name) {
        this.updatedBy = email;
        this.updatedByName = name;
    }
}
