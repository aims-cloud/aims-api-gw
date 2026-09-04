package com.innogrid.aims.common.util;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import com.innogrid.aims.common.exception.BusinessException;

import static org.junit.jupiter.api.Assertions.*;

class SortSupportTest {

    private static final Set<String> ALLOWED = Set.of("name", "createdAt");
    private static final Sort FALLBACK = Sort.by(Sort.Direction.DESC, "createdAt");

    @DisplayName("필드와 방향을 해석한다")
    @Test
    void parses_field_and_direction() {
        assertEquals(Sort.by(Sort.Direction.DESC, "createdAt"),
                SortSupport.parse("createdAt,desc", ALLOWED, FALLBACK));
        assertEquals(Sort.by(Sort.Direction.ASC, "name"),
                SortSupport.parse("name,asc", ALLOWED, FALLBACK));
    }

    @DisplayName("방향을 생략하면 오름차순이다")
    @Test
    void omitted_direction_ascending() {
        assertEquals(Sort.by(Sort.Direction.ASC, "name"),
                SortSupport.parse("name", ALLOWED, FALLBACK));
    }

    @DisplayName("대소문자와 공백을 허용한다")
    @Test
    void lenient_case_and_whitespace() {
        assertEquals(Sort.by(Sort.Direction.DESC, "name"),
                SortSupport.parse("  name , DESC ", ALLOWED, FALLBACK));
    }

    @DisplayName("값이 없으면 기본 정렬을 쓴다")
    @Test
    void no_value_uses_default_sort() {
        assertEquals(FALLBACK, SortSupport.parse(null, ALLOWED, FALLBACK));
        assertEquals(FALLBACK, SortSupport.parse("  ", ALLOWED, FALLBACK));
    }

    @DisplayName("허용하지 않는 필드는 400 이다")
    @Test
    void disallowed_field_400() {
        // 여기서 만든 Sort 가 그대로 order by 로 들어가므로 화이트리스트가 필요하다
        BusinessException e = assertThrows(BusinessException.class,
                () -> SortSupport.parse("password,asc", ALLOWED, FALLBACK));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("password"), e.getMessage());
    }

    @DisplayName("알 수 없는 방향은 400 이다")
    @Test
    void unknown_direction_400() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(BusinessException.class,
                        () -> SortSupport.parse("name,up", ALLOWED, FALLBACK)).getStatus());
    }

    @DisplayName("형식이 틀리면 400 이다")
    @Test
    void malformed_format_400() {
        assertThrows(BusinessException.class,
                () -> SortSupport.parse("name,asc,extra", ALLOWED, FALLBACK));
    }
}
