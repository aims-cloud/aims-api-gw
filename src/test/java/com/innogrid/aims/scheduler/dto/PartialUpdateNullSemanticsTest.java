package com.innogrid.aims.scheduler.dto;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 부분 수정(PUT)에서 "키 없음"과 "명시적 null"을 구분하는 역직렬화 형태를 고정한다.
 *
 * <p>정책 수정은 "키가 있으면 반영, 없으면 유지"이고 {@code resourceType}에 명시적 null을 주면
 * 공통 정책으로 전환된다. record의 일반 필드는 두 경우가 모두 null이라 구분이 안 된다.
 * 스프링이 구성한 Jackson 3 매퍼로 두 후보를 실측한 결과:</p>
 * <ul>
 *   <li>{@code Optional} — 키 없음도 명시적 null도 {@code Optional.empty()}. <b>구분 불가.</b></li>
 *   <li>{@code JsonNode} — 키 없음은 {@code null}, 명시적 null은 {@code NullNode}. <b>구분 가능.</b></li>
 * </ul>
 * <p>그래서 정책 요청 DTO의 "비울 수 있는" 세 필드는 {@code JsonNode}로 받는다. 이 테스트는 그 전제가
 * 라이브러리 업그레이드로 깨지면 바로 알리기 위한 것이다.</p>
 */
@SpringBootTest
class PartialUpdateNullSemanticsTest {

    @Autowired
    ObjectMapper objectMapper;

    record WithOptional(Optional<String> resourceType, String description) {}

    record WithNode(JsonNode resourceType, String description) {}

    @DisplayName("Optional 은 키 없음과 명시적 null 을 구분하지 못한다 그래서 쓰지 않는다")
    @Test
    void optional_cannot_distinguish_null() {
        WithOptional absent = objectMapper.readValue("{\"description\":\"d\"}", WithOptional.class);
        WithOptional explicitNull = objectMapper.readValue("{\"resourceType\":null}", WithOptional.class);
        WithOptional present = objectMapper.readValue("{\"resourceType\":\"VM\"}", WithOptional.class);

        assertEquals(Optional.of("VM"), present.resourceType());
        assertEquals(absent.resourceType(), explicitNull.resourceType(),
                "구분이 되기 시작했다면 이 테스트를 갱신하고 DTO 형태를 재검토한다");
    }

    @DisplayName("JsonNode 는 키 없음과 명시적 null 을 구분한다")
    @Test
    void jsonnode_distinguishes_null() {
        WithNode absent = objectMapper.readValue("{\"description\":\"d\"}", WithNode.class);
        WithNode explicitNull = objectMapper.readValue("{\"resourceType\":null}", WithNode.class);
        WithNode present = objectMapper.readValue("{\"resourceType\":\"VM\"}", WithNode.class);

        assertNull(absent.resourceType(), "키가 없으면 필드는 null — 유지로 해석");
        assertNotNull(explicitNull.resourceType(), "명시적 null 은 NullNode 로 들어온다 — 비움으로 해석");
        assertTrue(explicitNull.resourceType().isNull());
        assertEquals("VM", present.resourceType().asString());
    }
}
