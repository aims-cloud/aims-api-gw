package com.innogrid.aims.monitoring.dto.resourceexplorer.node.service;

import java.util.List;

/**
 * 위저드 2단계 검증 결과. {@code valid} 는 입력 규칙 위반이 없을 때만 true 이고 {@code issues} 의 field 는 요청 JSON 경로다.
 * {@code observations} 는 로그 경로별 최근 24시간 Loki 관측이며 참고 정보다 — 아직 수집되지 않는 파일을 등록하는 것이
 * 이 기능의 목적이므로 미관측(NOT_OBSERVED)은 valid 에 영향을 주지 않는다.
 */
public record CollectServiceValidationResponse(
        boolean valid,
        List<Issue> issues,
        List<PathObservation> observations
) {
    public record Issue(String field, String message) {}

    /** OBSERVED: 최근 24시간 로그 있음 / NOT_OBSERVED: 없음 / UNKNOWN: 노드 라벨 매핑 미설정 또는 Loki 조회 실패 */
    public record PathObservation(String path, ObservationStatus status) {}

    public enum ObservationStatus { OBSERVED, NOT_OBSERVED, UNKNOWN }

    public CollectServiceValidationResponse {
        issues = issues == null ? List.of() : List.copyOf(issues);
        observations = observations == null ? List.of() : List.copyOf(observations);
    }

    public CollectServiceValidationResponse(boolean valid, List<Issue> issues) {
        this(valid, issues, List.of());
    }
}
