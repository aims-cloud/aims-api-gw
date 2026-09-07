package com.innogrid.aims.scheduler.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * MDC 전력 예산 원장 — MDC 한 곳이 쓸 수 있는 전력 총량(W).
 *
 * <p>지금까지 전력 상한은 <b>노드 한 대 기준뿐</b>이었다. 그런데 MDC 는 컨테이너·랙 단위로 들어오는
 * 수전 용량이 곧 상한이라, 노드 8대가 각자 정격 아래여도 합계가 계약 용량을 넘으면 실제로는 올릴 수 없다.
 * 이 표는 그 총량을 넣는 자리다. 단계 20 의 노드 전력 사양 원장이 <b>한 대의 정격</b>을 다뤘다면
 * 이 표는 <b>한 곳의 총량</b>을 다룬다.</p>
 *
 * <p>노드 전력 사양·MDC 링크 원장과 같은 유형이다 — 컨소시엄이 주지 않는 값이고 우리가 입력하므로
 * 저장해도 형상 규칙(0-1)에 맞는다. MDC 식별자의 <b>존재는 검증하지 않는다</b>: 검증하려면 원장 CRUD 가
 * 메트릭 저장소를 불러야 한다. 어느 노드에도 걸리지 않는 오타 항목은 아무 판정에도 쓰이지 않아 무해하다.</p>
 */
@Entity
@Table(name = "mdc_power_budgets",
       uniqueConstraints = @UniqueConstraint(name = "uq_mdc_power_budgets_mdc", columnNames = "mdc_id"))
public class MdcPowerBudget {

    /**
     * 값의 출처. {@code SPEC_SHEET} 는 수전 설계·계약 용량 문서, {@code MEASURED} 는 실측 연동을 위해
     * 값만 예약했다 — 채우는 경로는 아직 없다. 노드 전력 사양 원장과 같은 세 값이다.
     */
    public enum BudgetSource { MANUAL, SPEC_SHEET, MEASURED }

    public static final int MDC_ID_MAX = 100;
    public static final int DESCRIPTION_MAX = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** MDC 식별자 — Prometheus {@code mdc_id} 라벨과 같은 문자열. 라벨이 없는 노드는 {@code unknown} 으로 묶인다. */
    @Column(name = "mdc_id", nullable = false, length = MDC_ID_MAX)
    private String mdcId;

    /**
     * 전력 예산(W). 0 보다 크고 유한해야 한다.
     *
     * <p><b>크기 상한은 두지 않는다.</b> 노드 전력 사양 원장과 같은 판단이다 — 실데이터가 없는 지금 상한은
     * 추측일 뿐이라 정당한 규모를 막을 수 있다. MDC 한 곳이 수십 kW 에서 수백 kW 까지 간다. 무한대만 막는다:
     * 남은 여유가 {@code ∞ − 합계 = ∞}, 초과 판정이 항상 통과가 되어 예산이 사실상 없는 것과 같아지고
     * JSON 직렬화도 표준을 벗어난다.</p>
     */
    @Column(name = "budget_watts", nullable = false)
    private double budgetWatts;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BudgetSource source = BudgetSource.MANUAL;

    @Column(length = DESCRIPTION_MAX)
    private String description;

    // ── 감사 (전력 사양·링크 원장과 같은 4필드) ─────────────────
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

    protected MdcPowerBudget() {}

    public MdcPowerBudget(String mdcId, double budgetWatts) {
        this.mdcId = mdcId;
        this.budgetWatts = budgetWatts;
    }

    public void setCreatedBy(String email, String name) { this.createdBy = email; this.createdByName = name; }
    public void setUpdatedBy(String email, String name) { this.updatedBy = email; this.updatedByName = name; }

    public Long getId() { return id; }
    public String getMdcId() { return mdcId; }
    public void setMdcId(String mdcId) { this.mdcId = mdcId; }
    public double getBudgetWatts() { return budgetWatts; }
    public void setBudgetWatts(double budgetWatts) { this.budgetWatts = budgetWatts; }
    public BudgetSource getSource() { return source; }
    public void setSource(BudgetSource source) { this.source = source; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCreatedBy() { return createdBy; }
    public String getCreatedByName() { return createdByName; }
    public String getUpdatedBy() { return updatedBy; }
    public String getUpdatedByName() { return updatedByName; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
