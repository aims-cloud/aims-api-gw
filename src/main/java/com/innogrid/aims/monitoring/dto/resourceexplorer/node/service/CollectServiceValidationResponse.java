package com.innogrid.aims.monitoring.dto.resourceexplorer.node.service;

import java.util.List;

/**
 * 수집 설정 유효성 검증 결과 — 위저드 2단계 '다음' 클릭 시 호출한다.
 * (로그·트레이스 중 하나 이상 활성화, 로그 경로 존재 여부, 트레이스 포트 유효성 등)
 */
public record CollectServiceValidationResponse(
        boolean valid,
        List<Issue> issues
) {
    /** 검증 실패 항목 1건 — field 는 요청 필드 경로 (예: logCollection.logPaths[0]) */
    public record Issue(String field, String message) {}

    public CollectServiceValidationResponse {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
