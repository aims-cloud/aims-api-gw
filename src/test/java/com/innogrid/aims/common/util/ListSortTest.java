package com.innogrid.aims.common.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.innogrid.aims.common.exception.BusinessException;

import static org.junit.jupiter.api.Assertions.*;

class ListSortTest {

    record Row(String name, Double value, int order) {}

    private static final Map<String, ListSort.Field<Row>> FIELDS = Map.of(
            "name", ListSort.by(Row::name, String.CASE_INSENSITIVE_ORDER),
            "value", ListSort.by(Row::value),
            "legacy", ListSort.byNullsAsLargest(Row::value),
            "order", ListSort.by(Row::order));
    private static final Comparator<Row> TIE = Comparator.comparingInt(Row::order);

    private static final Row A = new Row("alpha", 10.0, 1);
    private static final Row B = new Row("Bravo", null, 2);
    private static final Row C = new Row("charlie", 30.0, 3);
    private static final Row D = new Row("delta", 10.0, 4);

    @DisplayName("필드와 방향을 해석하고 방향을 생략하면 오름차순이다")
    @Test
    void parses_field_and_direction() {
        assertEquals(List.of(1, 2, 3, 4), orders("order,asc"));
        assertEquals(List.of(4, 3, 2, 1), orders("order,desc"));
        assertEquals(List.of(1, 2, 3, 4), orders("order"));
    }

    @DisplayName("대소문자와 공백을 허용한다")
    @Test
    void lenient_case_and_whitespace() {
        assertEquals(List.of(4, 3, 2, 1), orders("  order , DESC "));
    }

    @DisplayName("값 없음은 오름·내림 어느 방향에서도 마지막이다")
    @Test
    void nulls_last_both_directions() {
        assertEquals(2, orders("value,asc").get(3));
        assertEquals(2, orders("value,desc").get(3));
    }

    @DisplayName("byNullsAsLargest 는 null 을 가장 큰 값으로 본다 — 오름차순 마지막, 내림차순 처음")
    @Test
    void nulls_as_largest() {
        assertEquals(List.of(1, 4, 3, 2), orders("legacy,asc"));
        assertEquals(List.of(2, 3, 4, 1), orders("legacy,desc"));
    }

    @DisplayName("byNullsAsLargest 는 Comparator.nullsLast 를 통째로 뒤집던 이전 방식과 순서가 같다")
    @Test
    void nulls_as_largest_matches_reversed_nulls_last() {
        Comparator<Row> legacy = Comparator.comparing(Row::value, Comparator.nullsLast(Comparator.<Double>naturalOrder()))
                .thenComparing(TIE);
        for (boolean desc : new boolean[]{false, true}) {
            List<Row> expected = new ArrayList<>(List.of(C, A, D, B));
            expected.sort(desc ? legacy.reversed() : legacy);
            List<Row> actual = new ArrayList<>(List.of(C, A, D, B));
            actual.sort(ListSort.comparator(desc ? "legacy,desc" : "legacy,asc", FIELDS, TIE));
            assertEquals(expected, actual);
        }
    }

    @DisplayName("동률 보조 키는 1차 키와 같은 방향을 따른다")
    @Test
    void tie_breaker_follows_direction() {
        assertEquals(List.of(1, 4, 3, 2), orders("value,asc"));    // 10.0 동률 → order 1, 4
        assertEquals(List.of(3, 4, 1, 2), orders("value,desc"));   // 10.0 동률 → order 4, 1
    }

    @DisplayName("지정 순서(대소문자 무시)를 쓴다")
    @Test
    void custom_order() {
        assertEquals(List.of(1, 2, 3, 4), orders("name,asc"));     // alpha, Bravo, charlie, delta
    }

    @DisplayName("허용하지 않는 필드는 가능한 값을 이름순으로 담은 400 이다")
    @Test
    void unknown_field_is_bad_request() {
        BusinessException ex = assertThrows(BusinessException.class, () -> ListSort.comparator("size,asc", FIELDS, TIE));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("정렬할 수 없는 필드입니다: size (가능한 값: legacy, name, order, value)", ex.getMessage());
    }

    @DisplayName("asc·desc 가 아닌 방향은 400 이다 — 예전처럼 오름차순으로 넘기지 않는다")
    @Test
    void invalid_direction_is_bad_request() {
        BusinessException ex = assertThrows(BusinessException.class, () -> ListSort.comparator("order,xyz", FIELDS, TIE));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("정렬 방향은 asc 또는 desc 여야 합니다: xyz", ex.getMessage());
    }

    @DisplayName("빈 값·세 조각 이상은 400 이다")
    @Test
    void blank_or_too_many_parts_is_bad_request() {
        assertThrows(BusinessException.class, () -> ListSort.comparator("", FIELDS, TIE));
        assertThrows(BusinessException.class, () -> ListSort.comparator(null, FIELDS, TIE));
        assertThrows(BusinessException.class, () -> ListSort.comparator("order,asc,desc", FIELDS, TIE));
    }

    @DisplayName("보조 키가 없으면 1차 키만으로 정렬한다")
    @Test
    void no_tie_breaker() {
        List<Row> rows = new ArrayList<>(List.of(D, C, B, A));
        rows.sort(ListSort.comparator("name,desc", FIELDS, null));
        assertEquals(List.of(D, C, B, A), rows);
    }

    @DisplayName("Spec 은 필드·보조 키를 묶어 두고 같은 규칙으로 비교자를 만든다")
    @Test
    void spec_reuses_fields_and_tie_breaker() {
        ListSort.Spec<Row> spec = ListSort.spec(FIELDS, TIE);
        List<Row> rows = new ArrayList<>(List.of(C, A, D, B));
        rows.sort(spec.comparator("value,desc"));
        assertEquals(List.of(3, 4, 1, 2), rows.stream().map(Row::order).toList());
        assertThrows(BusinessException.class, () -> spec.comparator("value,up"));
    }

    private static List<Integer> orders(String sort) {
        List<Row> rows = new ArrayList<>(List.of(C, A, D, B));
        rows.sort(ListSort.comparator(sort, FIELDS, TIE));
        return rows.stream().map(Row::order).toList();
    }
}
