package com.innogrid.aims.common.util;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import com.innogrid.aims.common.exception.BusinessException;

import static org.junit.jupiter.api.Assertions.*;

class SortSupportTest {

    private static final Set<String> ALLOWED = Set.of("name", "createdAt");
    private static final Sort FALLBACK = Sort.by(Sort.Direction.DESC, "createdAt");

    @Test
    void 필드와_방향을_해석한다() {
        assertEquals(Sort.by(Sort.Direction.DESC, "createdAt"),
                SortSupport.parse("createdAt,desc", ALLOWED, FALLBACK));
        assertEquals(Sort.by(Sort.Direction.ASC, "name"),
                SortSupport.parse("name,asc", ALLOWED, FALLBACK));
    }

    @Test
    void 방향을_생략하면_오름차순이다() {
        assertEquals(Sort.by(Sort.Direction.ASC, "name"),
                SortSupport.parse("name", ALLOWED, FALLBACK));
    }

    @Test
    void 대소문자와_공백을_허용한다() {
        assertEquals(Sort.by(Sort.Direction.DESC, "name"),
                SortSupport.parse("  name , DESC ", ALLOWED, FALLBACK));
    }

    @Test
    void 값이_없으면_기본_정렬을_쓴다() {
        assertEquals(FALLBACK, SortSupport.parse(null, ALLOWED, FALLBACK));
        assertEquals(FALLBACK, SortSupport.parse("  ", ALLOWED, FALLBACK));
    }

    @Test
    void 허용하지_않는_필드는_400_이다() {
        // 여기서 만든 Sort 가 그대로 order by 로 들어가므로 화이트리스트가 필요하다
        BusinessException e = assertThrows(BusinessException.class,
                () -> SortSupport.parse("password,asc", ALLOWED, FALLBACK));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("password"), e.getMessage());
    }

    @Test
    void 알_수_없는_방향은_400_이다() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(BusinessException.class,
                        () -> SortSupport.parse("name,up", ALLOWED, FALLBACK)).getStatus());
    }

    @Test
    void 형식이_틀리면_400_이다() {
        assertThrows(BusinessException.class,
                () -> SortSupport.parse("name,asc,extra", ALLOWED, FALLBACK));
    }
}
