package com.innogrid.aims.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 업무 규칙 위반을 HTTP 상태와 함께 알리는 예외. {@link GlobalExceptionHandler} 가 {@code ApiResponse} 오류 형식으로 내린다.
 *
 * <p>자주 쓰는 상태는 정적 생성 함수를 쓴다 — 클래스마다 {@code badRequest()}·{@code notFound()} 도우미를 따로 두지 않는다.
 * {@code ResponseStatusException} 은 공통 처리기를 거치지 않아 응답 형식이 달라지므로 501 도 여기서 만든다.</p>
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** 400 — 요청 값이 형식·범위·조합 규칙에 맞지 않음 */
    public static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }

    /** 404 — 대상이 없거나 요청자 범위 밖 */
    public static BusinessException notFound(String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, message);
    }

    /** 409 — 현재 상태와 충돌(중복 이름, 진행 중인 작업 등) */
    public static BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, message);
    }

    /** 501 — 계약은 열려 있으나 데이터 원천·기능이 아직 없음 */
    public static BusinessException notImplemented(String message) {
        return new BusinessException(HttpStatus.NOT_IMPLEMENTED, message);
    }
}
