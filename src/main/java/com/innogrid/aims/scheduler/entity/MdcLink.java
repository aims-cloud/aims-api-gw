package com.innogrid.aims.scheduler.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * MDC 두 곳 사이의 연결 품질 원장 — 지연·대역폭·손실·상태.
 *
 * <p>배치 엔진이 "데이터가 있는 MDC 와 노드가 있는 MDC 사이가 얼마나 좋은가"를 물을 때 읽는 표다.
 * 정책 {@code affinityEnabled} 와 워크로드 {@code dataMdcId} 가 모두 있을 때 제약 C5(DOWN 제외, 전 엔진 공통)와
 * weighted 엔진의 링크 점수가 읽는다. 대역폭은 저장·응답만 하고 아직 점수에 쓰지 않는다.</p>
 *
 * <p><b>쌍은 방향이 없다.</b> A→B 와 B→A 를 따로 두면 매트릭스가 비대칭이 되고 한쪽만 갱신되는 사고가 난다.
 * 그래서 저장할 때 두 식별자를 <b>사전순으로 정렬</b>해 작은 쪽을 {@code mdcA} 에 둔다. 유니크 제약은
 * 정렬된 쌍에 걸리므로 역방향 등록이 자연히 중복으로 잡힌다. 정렬은 {@link Pair#of} 한 곳에서만 한다.</p>
 *
 * <p>MDC 식별자는 노드 라벨 {@code mdc_id} 와 같은 문자열이며 여기서 존재를 검증하지 않는다 — 검증하려면
 * 원장 CRUD 가 메트릭 저장소를 불러야 한다. 오타 링크는 매트릭스에 그대로 보이고 배치에서는 미등록 쌍으로
 * 취급된다(제약 통과 · 점수는 기본값 0.5).</p>
 */
@Entity
@Table(name = "mdc_links",
       uniqueConstraints = @UniqueConstraint(name = "uq_mdc_links_pair", columnNames = {"mdc_a", "mdc_b"}),
       indexes = {
           @Index(name = "idx_mdc_links_mdc_a", columnList = "mdc_a"),
           @Index(name = "idx_mdc_links_mdc_b", columnList = "mdc_b")
       })
public class MdcLink {

    /** 링크 상태. DOWN 은 배치 제약 C5 가 후보 제외에 쓰고, DEGRADED 는 점수 가산 비용으로만 반영된다. */
    public enum LinkStatus { UP, DEGRADED, DOWN }

    /** 값의 출처. PROBE 는 자동 측정용으로 예약만 해 두었다 — 측정기는 아직 없다. */
    public enum LinkSource { MANUAL, PROBE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 사전순으로 작은 MDC 식별자.
     *
     * <p>컬럼 이름을 명시한다 — 스프링의 카멜→스네이크 변환은 <b>마지막 글자</b>를 검사하지 않아
     * {@code mdcA} 가 {@code mdc_a} 가 아니라 {@code mdca} 로 만들어진다(2026-09-02 배포에서 실측).
     * DDL 스크립트와 같은 이름이 되도록 고정한다.</p>
     */
    @Column(name = "mdc_a", nullable = false, length = 50)
    private String mdcA;

    /** 사전순으로 큰 MDC 식별자. 컬럼 이름을 명시하는 이유는 {@link #mdcA} 와 같다. */
    @Column(name = "mdc_b", nullable = false, length = 50)
    private String mdcB;

    /** 왕복 지연(ms). 0 이상. 필수 — 비용 함수의 주 입력이다. */
    @Column(nullable = false)
    private double latencyMs;

    /** 대역폭(Mbps). 모르면 null. */
    private Double bandwidthMbps;

    /** 패킷 손실률(%). 0~100, 모르면 null. */
    private Double packetLossPercent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LinkStatus status = LinkStatus.UP;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LinkSource source = LinkSource.MANUAL;

    @Column(length = 300)
    private String description;

    // ── 감사 (스케줄과 같은 4필드) ──────────────────────────────
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

    protected MdcLink() {}

    /** 두 식별자는 순서에 상관없이 넣는다 — 저장 순서는 {@link Pair#of} 가 정한다. */
    public MdcLink(String mdcA, String mdcB, double latencyMs) {
        repair(mdcA, mdcB);
        this.latencyMs = latencyMs;
    }

    /**
     * 정렬된 MDC 쌍. {@code of("b","a")} 와 {@code of("a","b")} 는 같다.
     * 같은 식별자 둘은 링크가 아니므로 거부한다 — 서비스가 먼저 400 으로 막고, 여기서는 불변식으로 지킨다.
     */
    public record Pair(String a, String b) {
        public static Pair of(String x, String y) {
            if (x == null || y == null) throw new IllegalArgumentException("MDC 식별자는 null 일 수 없습니다");
            if (x.equals(y)) throw new IllegalArgumentException("같은 MDC 사이에는 링크를 둘 수 없습니다: " + x);
            return x.compareTo(y) < 0 ? new Pair(x, y) : new Pair(y, x);
        }
    }

    /** 쌍을 바꾼다(수정 시). 항상 정렬해 저장한다. */
    public void repair(String x, String y) {
        Pair p = Pair.of(x, y);
        this.mdcA = p.a();
        this.mdcB = p.b();
    }

    /** 이 링크가 주어진 MDC 에 걸려 있는가. */
    public boolean connects(String mdcId) {
        return mdcA.equals(mdcId) || mdcB.equals(mdcId);
    }

    /** 주어진 MDC 의 반대편. 걸려 있지 않으면 null. */
    public String other(String mdcId) {
        if (mdcA.equals(mdcId)) return mdcB;
        if (mdcB.equals(mdcId)) return mdcA;
        return null;
    }

    public Pair pair() { return new Pair(mdcA, mdcB); }

    // Getters
    public Long getId() { return id; }
    public String getMdcA() { return mdcA; }
    public String getMdcB() { return mdcB; }
    public double getLatencyMs() { return latencyMs; }
    public Double getBandwidthMbps() { return bandwidthMbps; }
    public Double getPacketLossPercent() { return packetLossPercent; }
    public LinkStatus getStatus() { return status; }
    public LinkSource getSource() { return source; }
    public String getDescription() { return description; }
    public String getCreatedBy() { return createdBy; }
    public String getCreatedByName() { return createdByName; }
    public String getUpdatedBy() { return updatedBy; }
    public String getUpdatedByName() { return updatedByName; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    // Setters
    public void setLatencyMs(double latencyMs) { this.latencyMs = latencyMs; }
    public void setBandwidthMbps(Double bandwidthMbps) { this.bandwidthMbps = bandwidthMbps; }
    public void setPacketLossPercent(Double packetLossPercent) { this.packetLossPercent = packetLossPercent; }
    public void setStatus(LinkStatus status) { this.status = status; }
    public void setSource(LinkSource source) { this.source = source; }
    public void setDescription(String description) { this.description = description; }
    public void setCreatedBy(String email, String name) {
        this.createdBy = email;
        this.createdByName = name;
    }
    public void setUpdatedBy(String email, String name) {
        this.updatedBy = email;
        this.updatedByName = name;
    }
}
