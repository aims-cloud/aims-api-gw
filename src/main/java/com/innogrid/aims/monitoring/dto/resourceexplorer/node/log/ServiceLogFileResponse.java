package com.innogrid.aims.monitoring.dto.resourceexplorer.node.log;

/** 서비스의 수집 로그 파일 1개 — 로그 상세 페이지 좌측 체크박스와 섹션 헤더("총 N건")에 사용한다. */
public record ServiceLogFileResponse(
        String fileName,                // 예: access.log
        String path,                    // 등록 전체 경로: 조회 키
        long totalCount
) {
}
