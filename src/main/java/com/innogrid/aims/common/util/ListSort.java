package com.innogrid.aims.common.util;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import com.innogrid.aims.common.exception.BusinessException;

/**
 * 메모리에 올린 목록의 {@code sort} 파라미터({@code "필드,asc|desc"})를 {@link Comparator} 로 해석한다.
 *
 * <p>{@link SortSupport} 는 JPA {@code Sort} 를 만들어 DB 가 정렬하게 한다. 이 클래스는 Thanos·외부 API 결과처럼
 * 이미 메모리에 있는 행을 정렬할 때 쓴다. 형식 규칙은 같다 — 방향을 생략하면 오름차순, 허용 목록에 없는 필드·
 * {@code asc}/{@code desc} 가 아닌 방향·세 조각 이상은 400 이다.</p>
 *
 * <p>값 없음(null) 위치는 필드마다 둘 중 하나를 고른다 — 기존 목록의 동작을 그대로 옮기려고 두 방식을 모두 둔다.</p>
 * <ul>
 *   <li>{@link #by} — 오름·내림 어느 방향에서도 마지막.</li>
 *   <li>{@link #byNullsAsLargest} — null 을 가장 큰 값으로 본다. 오름차순이면 마지막, 내림차순이면 처음.</li>
 * </ul>
 * <p>동률 보조 키는 1차 키와 같은 방향을 따른다 — 내림차순이면 보조 키도 내림차순.</p>
 *
 * <p>스프링 빈이 아니다. {@link SortSupport} 와 같은 정적 도우미다.</p>
 */
public final class ListSort {

    private ListSort() {}

    /** 정렬 필드 하나 — 방향을 받아 그 방향의 비교자를 만든다. 값 없음 처리를 방향마다 맞추려고 방향을 안에서 다룬다 */
    @FunctionalInterface
    public interface Field<T> {
        Comparator<T> comparator(boolean descending);
    }

    /**
     * 목록 하나의 정렬 정의 — 허용 필드와 동률 보조 키. 클래스 상수로 한 번 만들어 두고 요청마다 {@link #comparator(String)} 를 부른다.
     * <pre>{@code
     * static final ListSort.Spec<Row> SORT = ListSort.spec(Map.of(
     *         "name", ListSort.by(Row::name, String.CASE_INSENSITIVE_ORDER),
     *         "size", ListSort.by(Row::size)),
     *         Comparator.comparing(Row::name));
     * }</pre>
     */
    public static final class Spec<T> {

        private final Map<String, Field<T>> fields;
        private final Comparator<T> tieBreaker;

        private Spec(Map<String, Field<T>> fields, Comparator<T> tieBreaker) {
            this.fields = Map.copyOf(fields);
            this.tieBreaker = tieBreaker;
        }

        /** {@link ListSort#comparator(String, Map, Comparator)} 와 같다 — 형식 오류·허용하지 않는 필드·방향은 400 */
        public Comparator<T> comparator(String sort) {
            return ListSort.comparator(sort, fields, tieBreaker);
        }
    }

    /** 정렬 정의를 만든다. tieBreaker 는 오름차순 기준 보조 키, 없으면 null */
    public static <T> Spec<T> spec(Map<String, Field<T>> fields, Comparator<T> tieBreaker) {
        return new Spec<>(fields, tieBreaker);
    }

    /** 자연 순서 필드 — 값 없음은 방향과 무관하게 마지막 */
    public static <T, U extends Comparable<? super U>> Field<T> by(Function<T, U> key) {
        return by(key, Comparator.naturalOrder());
    }

    /** 지정 순서 필드(대소문자 무시 문자열 등) — 값 없음은 방향과 무관하게 마지막 */
    public static <T, U> Field<T> by(Function<T, U> key, Comparator<? super U> order) {
        return descending -> {
            Comparator<U> values = descending ? reversed(order) : narrowed(order);
            return Comparator.comparing(key, Comparator.nullsLast(values));
        };
    }

    /** 자연 순서 필드 — null 을 가장 큰 값으로 본다(오름차순 마지막, 내림차순 처음) */
    public static <T, U extends Comparable<? super U>> Field<T> byNullsAsLargest(Function<T, U> key) {
        return byNullsAsLargest(key, Comparator.naturalOrder());
    }

    /**
     * 지정 순서 필드 — null 을 가장 큰 값으로 본다(오름차순 마지막, 내림차순 처음).
     * {@code Comparator.nullsLast} 비교자를 내림차순에서 통째로 뒤집던 목록의 동작과 같다.
     */
    public static <T, U> Field<T> byNullsAsLargest(Function<T, U> key, Comparator<? super U> order) {
        return descending -> descending
                ? Comparator.comparing(key, Comparator.nullsFirst(ListSort.<U>reversed(order)))
                : Comparator.comparing(key, Comparator.nullsLast(ListSort.<U>narrowed(order)));
    }

    /**
     * {@code "필드,방향"} 을 비교자로 옮긴다.
     *
     * @param sort       요청 값. 방향을 생략하면 오름차순
     * @param fields     허용 필드 이름 → 정렬 필드. 오류 문구의 "가능한 값"은 이름순
     * @param tieBreaker 1차 키가 같을 때 쓸 보조 키(오름차순 기준). 없으면 null. 내림차순이면 뒤집어 쓴다
     * @throws BusinessException 비었거나 형식이 틀렸거나 허용하지 않는 필드·방향이면 400 — 조회 전에 부른다
     */
    public static <T> Comparator<T> comparator(String sort, Map<String, Field<T>> fields, Comparator<T> tieBreaker) {
        if (sort == null || sort.isBlank()) {
            throw BusinessException.badRequest("정렬 값이 비어 있습니다 (형식: 필드,asc|desc)");
        }
        String[] parts = sort.split(",");
        if (parts.length > 2) {
            throw BusinessException.badRequest("정렬 형식이 올바르지 않습니다: " + sort + " (형식: 필드,asc|desc)");
        }
        String name = parts[0].trim();
        Field<T> field = fields.get(name);
        if (field == null) {
            throw BusinessException.badRequest(String.format("정렬할 수 없는 필드입니다: %s (가능한 값: %s)",
                    name, String.join(", ", fields.keySet().stream().sorted().toList())));
        }
        boolean descending = parts.length == 2 && descending(parts[1]);

        Comparator<T> comparator = field.comparator(descending);
        if (tieBreaker != null) {
            comparator = comparator.thenComparing(descending ? tieBreaker.reversed() : tieBreaker);
        }
        return comparator;
    }

    private static boolean descending(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "asc" -> false;
            case "desc" -> true;
            default -> throw BusinessException.badRequest("정렬 방향은 asc 또는 desc 여야 합니다: " + raw.trim());
        };
    }

    @SuppressWarnings("unchecked")
    private static <U> Comparator<U> narrowed(Comparator<? super U> order) {
        return (Comparator<U>) order;
    }

    private static <U> Comparator<U> reversed(Comparator<? super U> order) {
        Comparator<U> narrowed = narrowed(order);
        return narrowed.reversed();
    }
}
