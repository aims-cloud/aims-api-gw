package com.innogrid.aims.common.dto;

import java.util.List;

/**
 * 공통 페이징 응답. page 는 1부터 시작한다.
 * 목록형 API 의 페이징 표준 — 신규 목록 API 는 이 형식을 따른다.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    /** 전체 목록에서 요청 페이지 구간을 잘라 응답을 만든다. */
    public static <T> PageResponse<T> of(List<T> all, int page, int size) {
        int totalPages = (int) Math.ceil((double) all.size() / size);
        int fromIndex = Math.min((page - 1) * size, all.size());
        int toIndex = Math.min(fromIndex + size, all.size());
        return new PageResponse<>(all.subList(fromIndex, toIndex), page, size, all.size(), totalPages);
    }
}
