package com.innogrid.aims.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.innogrid.aims.common.dto.ApiResponse;

import static org.junit.jupiter.api.Assertions.*;

class BusinessExceptionTest {

    @DisplayName("정적 생성 함수는 상태와 문구를 그대로 담는다")
    @Test
    void factories_carry_status_and_message() {
        assertStatus(HttpStatus.BAD_REQUEST, BusinessException.badRequest("잘못된 요청"), "잘못된 요청");
        assertStatus(HttpStatus.NOT_FOUND, BusinessException.notFound("노드를 찾을 수 없습니다."), "노드를 찾을 수 없습니다.");
        assertStatus(HttpStatus.CONFLICT, BusinessException.conflict("이미 있습니다."), "이미 있습니다.");
        assertStatus(HttpStatus.NOT_IMPLEMENTED, BusinessException.notImplemented("구현 전입니다."), "구현 전입니다.");
    }

    @DisplayName("501 도 공통 처리기에서 ApiResponse 오류 형식으로 나간다")
    @Test
    void not_implemented_goes_through_global_handler() {
        ResponseEntity<ApiResponse<Void>> response = new GlobalExceptionHandler()
                .handleBusiness(BusinessException.notImplemented("구현 전입니다."));

        assertEquals(HttpStatus.NOT_IMPLEMENTED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("구현 전입니다.", response.getBody().message());
    }

    private static void assertStatus(HttpStatus expected, BusinessException ex, String message) {
        assertEquals(expected, ex.getStatus());
        assertEquals(message, ex.getMessage());
    }
}
