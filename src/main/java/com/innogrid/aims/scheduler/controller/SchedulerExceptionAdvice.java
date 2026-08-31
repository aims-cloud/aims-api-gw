package com.innogrid.aims.scheduler.controller;

import java.util.stream.Collectors;

import com.innogrid.aims.common.dto.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * 스케줄러 API 의 요청 검증 오류를 400 으로 돌려준다.
 *
 * <p><b>왜 필요한가</b> — 이 프로젝트는 Spring Framework 7 이라 클래스에 {@code @Validated}
 * 를 붙이지 않아도 {@code @RequestParam} 의 {@code @Min}·{@code @Max} 가 동작한다.
 * 그런데 그때 나오는 {@link HandlerMethodValidationException} 을 처리하는 곳이 없어
 * 스프링 기본 처리로 넘어가고, 기본 처리는 {@code sendError} 로 {@code /error} 를 다시
 * 태운다. 보안 설정이 모든 요청에 인증을 요구하는데 이 재요청에는 인증 정보가 실리지
 * 않아, 결국 클라이언트는 <b>400 이 아니라 403</b> 을 받는다.</p>
 *
 * <p>그래서 {@code sendError} 를 타지 않고 {@link ResponseEntity} 를 직접 만들어 돌려준다.
 * {@code page=0} 같은 잘못된 페이징 값이 확실하게 400 이 되며, 목록 응답의
 * {@code totalPages} 가 0 나눗셈으로 비정상 값이 되는 것도 함께 막힌다.</p>
 *
 * <p><b>적용 범위를 스케줄러 패키지로 한정</b>한다. 다른 패키지의 컨트롤러는 이 advice 의
 * 후보에서 제외되어 지금과 똑같이 동작한다. 같은 문제가 다른 도메인에도 있지만 담당
 * 범위가 아니라 손대지 않는다 — 공통으로 고치려면 전역 예외 핸들러에 처리기를 하나
 * 추가하면 된다.</p>
 *
 * <p>{@code BusinessException} 은 여기서 다루지 않는다. 전역 핸들러와 후보가 겹치면
 * 어느 쪽이 처리할지가 우선순위에 좌우되어, 스케줄러만 응답 형태가 달라질 수 있다.</p>
 */
@RestControllerAdvice(basePackages = "com.innogrid.aims.scheduler")
public class SchedulerExceptionAdvice {

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleParameterValidation(
            HandlerMethodValidationException ex) {
        String message = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> result.getMethodParameter().getParameterName()
                                + ": " + error.getDefaultMessage()))
                .distinct()
                .collect(Collectors.joining(", "));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(message.isBlank() ? "요청 파라미터가 올바르지 않습니다" : message));
    }
}
