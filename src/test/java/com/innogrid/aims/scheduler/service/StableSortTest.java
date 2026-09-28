package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 페이징 정렬의 {@code id} 보조 키 부착 규칙 — 방향 추종, 이미 있으면 그대로, 비어 있으면 내림차순. */
class StableSortTest {

    @DisplayName("내림차순 1차 키에는 id 내림차순이 붙는다")
    @Test
    void desc_primary_gets_desc_id() {
        Sort out = StableSort.withIdTiebreaker(Sort.by(Sort.Direction.DESC, "createdAt"));

        assertEquals(List.of(Sort.Order.desc("createdAt"), Sort.Order.desc("id")), out.toList());
    }

    @DisplayName("오름차순 1차 키에는 id 오름차순이 붙는다")
    @Test
    void asc_primary_gets_asc_id() {
        Sort out = StableSort.withIdTiebreaker(Sort.by(Sort.Direction.ASC, "status"));

        assertEquals(List.of(Sort.Order.asc("status"), Sort.Order.asc("id")), out.toList());
    }

    @DisplayName("id 가 이미 있으면 그대로 두고 중복으로 붙이지 않는다")
    @Test
    void existing_id_is_left_alone() {
        Sort idOnly = Sort.by(Sort.Direction.ASC, "id");
        Sort statusThenId = Sort.by(Sort.Direction.DESC, "status").and(Sort.by("id"));

        assertSame(idOnly, StableSort.withIdTiebreaker(idOnly));
        assertSame(statusThenId, StableSort.withIdTiebreaker(statusThenId));
    }

    @DisplayName("다중 키 정렬에서 id 는 첫 키의 방향을 따르고 맨 뒤에 붙는다")
    @Test
    void multi_key_follows_first_direction() {
        Sort in = Sort.by(Sort.Order.desc("status"), Sort.Order.asc("name"));

        assertEquals(List.of(Sort.Order.desc("status"), Sort.Order.asc("name"), Sort.Order.desc("id")),
                StableSort.withIdTiebreaker(in).toList());
    }

    @DisplayName("정렬이 비어 있으면 id 내림차순이다")
    @Test
    void unsorted_becomes_id_desc() {
        assertEquals(List.of(Sort.Order.desc("id")), StableSort.withIdTiebreaker(Sort.unsorted()).toList());
    }
}
