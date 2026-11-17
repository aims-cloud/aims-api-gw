package com.innogrid.aims.scheduler.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * 단건 배치 1회의 입력과 그 결정 — 실데이터가 들어온 뒤 그 결정을 다시 계산해 보려고 남긴다.
 *
 * <p>스케줄 실행은 {@link ScheduleRunInput}이 입력을 남기지만, 단건 배치({@code POST /workloads/{workloadId}/schedule})는
 * 아무것도 남기지 않았다. 배치 이력({@link SchedulingHistory})도 단건 배치는 성공한 결정만 남겨, 실패한 결정은 어디에도
 * 없었다. 이 행은 배치 직전의 입력을 {@link ScheduleRunInput}과 같은 형식의 JSON 문자열({@code payload} · 대상 워크로드
 * 1건)로 담고, 엔진이 낸 결정(실행 계층에 넘기기 전)을 같은 행에 둔다 — 배치 이력과 잇지 않아도 대조할 수 있게.</p>
 *
 * <p>실행 이력과 같은 기간만 남긴다(보존 정리가 {@code capturedAt} 기준으로 지운다). 외래키는 두지 않는다 — 배치 이력의
 * 실행 연결과 같은 관례다. 워크로드를 지워도 이 행은 보존 기간까지 남는다.</p>
 */
@Entity
@Table(name = "placement_inputs", indexes = {
        @Index(name = "ix_placement_inputs_workload", columnList = "workloadId"),
        // 보존 정리가 이 열로 지운다
        @Index(name = "ix_placement_inputs_captured", columnList = "capturedAt")
})
public class PlacementInput {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long workloadId;

    /** payload 형식 판 — 스케줄 실행 입력과 같은 판을 쓴다. */
    private int formatVersion;

    private OffsetDateTime capturedAt;

    /** 실제로 쓴 엔진 id — 요청에 엔진이 없으면 기본 엔진이 들어간다. */
    private String engineId;

    /** 엔진 결정의 성공 여부. 실행 계층이 받지 않아 실패로 낮춘 결과가 아니다 — 그건 전달 기록에 남는다. */
    private boolean success;

    /** 엔진이 고른 노드. 실패한 결정이면 null. */
    private String selectedNodeId;

    /** 스케줄 실행 입력의 노브 지문과 같은 계산 — 두 표를 대조할 때 쓴다. */
    @Column(length = 12)
    private String knobsDigest;

    /** payload 문자열의 SHA-256 앞 12자. */
    @Column(length = 12)
    private String inputDigest;

    @Column(columnDefinition = "text")
    private String payload;

    protected PlacementInput() {}

    public PlacementInput(Long workloadId, int formatVersion, OffsetDateTime capturedAt, String engineId,
                          boolean success, String selectedNodeId, String knobsDigest, String inputDigest, String payload) {
        this.workloadId = workloadId;
        this.formatVersion = formatVersion;
        this.capturedAt = capturedAt;
        this.engineId = engineId;
        this.success = success;
        this.selectedNodeId = selectedNodeId;
        this.knobsDigest = knobsDigest;
        this.inputDigest = inputDigest;
        this.payload = payload;
    }

    public Long getId() { return id; }
    public Long getWorkloadId() { return workloadId; }
    public int getFormatVersion() { return formatVersion; }
    public OffsetDateTime getCapturedAt() { return capturedAt; }
    public String getEngineId() { return engineId; }
    public boolean isSuccess() { return success; }
    public String getSelectedNodeId() { return selectedNodeId; }
    public String getKnobsDigest() { return knobsDigest; }
    public String getInputDigest() { return inputDigest; }
    public String getPayload() { return payload; }
}
