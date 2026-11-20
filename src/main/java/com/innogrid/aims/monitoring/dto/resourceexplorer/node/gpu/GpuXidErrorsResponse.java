package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * XID Errors 상세 패널 — ECC DBE Errors 카드에서 열린다 (카드는 DBE 개수, 상세는 전체 XID 기준).
 * 해석(interpretations)은 XID 번호별 정적 지식 베이스에서 조회하며,
 * 원인이 복수 가능한 XID 는 후보 여러 개("해석 후보 N개"), 단일 원인이면 1개를 담는다.
 */
public record GpuXidErrorsResponse(
        long totalCount,
        OffsetDateTime lastOccurredAt,
        List<XidError> errors
) {
    /** XID 발생 이벤트 1건 — 워크로드 값은 발생 시점 스냅샷 (수집 전이면 null) */
    public record XidError(int xid, String gpuId, OffsetDateTime occurredAt,
                           int recurrence24h, Double workloadUtilPercent, Double workloadVramPercent,
                           List<Interpretation> interpretations) {}

    /** XID 해석 1건 — 원인 태그(드라이버 오류·시스템 메모리 손상·버스 오류·FB 손상 등)와 조치 안내 */
    public record Interpretation(String title, String titleEn, List<String> causeTags,
                                 String reason, String immediateAction,
                                 String investigation, String deepDiagnosisGuide) {}

    public GpuXidErrorsResponse {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
