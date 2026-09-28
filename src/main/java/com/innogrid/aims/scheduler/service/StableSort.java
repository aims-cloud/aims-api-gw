package com.innogrid.aims.scheduler.service;

import java.util.Objects;

import org.springframework.data.domain.Sort;

/**
 * 페이징 정렬에 {@code id} 보조 키를 붙인다. 1차 키가 같은 행(같은 상태, 같은 시각의 {@code createdAt} 등)은
 * DB 가 순서를 정하지 않으므로, 그대로 두면 페이지 경계에서 행이 겹치거나 빠진다.
 *
 * <p>방향은 첫 정렬 키를 따른다 — 기본 최신순이면 {@code id} 내림차순, {@code createdAt,asc} 면 오름차순이다.
 * 배치 이력에 붙일 때 실행 상세의 결정 목록(지금은 {@code createdAt} 오름차순만)에도 {@code id} 를 더하면
 * 두 응답의 행 순서가 같아진다. 알람·포털 로그 목록은 보조 키를 {@code id} 내림차순으로 고정하지만,
 * 스케줄러는 이 이유로 방향을 따른다.</p>
 *
 * <p>스프링 빈이 아니고 스케줄러 서비스 패키지 밖에서는 보이지 않는다. 공용 {@code SortSupport} 는 손대지 않는다.</p>
 */
final class StableSort {

    static final String ID = "id";

    private StableSort() {}

    /**
     * {@code sort} 에 {@code id} 가 이미 있으면 그대로, 없으면 첫 키의 방향으로 {@code id} 를 덧붙인다.
     * 정렬이 비어 있으면 {@code id} 내림차순 — 스케줄러 목록의 기본 정렬이 모두 최신순이라 같은 방향으로 둔다.
     */
    static Sort withIdTiebreaker(Sort sort) {
        Objects.requireNonNull(sort, "sort");
        if (sort.isUnsorted()) {
            return Sort.by(Sort.Direction.DESC, ID);
        }
        if (sort.getOrderFor(ID) != null) {
            return sort;
        }
        return sort.and(Sort.by(sort.iterator().next().getDirection(), ID));
    }
}
