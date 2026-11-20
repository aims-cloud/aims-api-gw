package com.innogrid.aims.scheduler.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * 스케줄 실행 1회의 배치 입력 — 그 회차를 다시 돌려 보려고 남긴다.
 *
 * <p>실행 이력({@link ScheduleRun})에는 집계와 노브 지문만, 배치 이력({@link SchedulingHistory})에는 고른 노드의 값만
 * 남아 회차를 재생할 수 없었다. 이 행은 배치 루프 직전의 노드 상태·정책 사본·링크·MDC 예산·실행 전력 상한·냉각 대기·대상
 * 워크로드·엔진 노브를 JSON 문자열 하나({@code payload})로 담는다. 형식은 {@code formatVersion}이 정한다.</p>
 *
 * <p>실행 이력 표에 열로 두지 않고 따로 둔 까닭: 실행 이력 목록·상세가 엔티티를 읽을 때 수십 KB가 넘는 열이 함께 실린다.
 * 실행 이력과 같은 기간만 남기고, 실행 이력을 지우는 두 자리(보존 정리·스케줄 삭제)에서 함께 지운다. 외래키는 두지 않는다 —
 * 배치 이력의 실행 연결과 같은 관례다.</p>
 */
@Entity
@Table(name = "schedule_run_inputs", indexes = {
        // 실행 한 회차에 한 행 — 재생·삭제 모두 실행 id로 찾는다
        @Index(name = "uq_schedule_run_inputs_run", columnList = "scheduleRunId", unique = true)
})
public class ScheduleRunInput {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long scheduleRunId;

    /** payload 형식 판. 담는 항목이 바뀌면 올린다. */
    private int formatVersion;

    private OffsetDateTime capturedAt;

    /** 실행 이력의 노브 지문과 같은 값 — 두 표를 대조할 때 쓴다. 계산이 실패했으면 null. */
    @Column(length = 12)
    private String knobsDigest;

    /** payload 문자열의 SHA-256 앞 12자. */
    @Column(length = 12)
    private String inputDigest;

    @Column(columnDefinition = "text")
    private String payload;

    protected ScheduleRunInput() {}

    public ScheduleRunInput(Long scheduleRunId, int formatVersion, OffsetDateTime capturedAt,
                            String knobsDigest, String inputDigest, String payload) {
        this.scheduleRunId = scheduleRunId;
        this.formatVersion = formatVersion;
        this.capturedAt = capturedAt;
        this.knobsDigest = knobsDigest;
        this.inputDigest = inputDigest;
        this.payload = payload;
    }

    public Long getId() { return id; }
    public Long getScheduleRunId() { return scheduleRunId; }
    public int getFormatVersion() { return formatVersion; }
    public OffsetDateTime getCapturedAt() { return capturedAt; }
    public String getKnobsDigest() { return knobsDigest; }
    public String getInputDigest() { return inputDigest; }
    public String getPayload() { return payload; }
}
