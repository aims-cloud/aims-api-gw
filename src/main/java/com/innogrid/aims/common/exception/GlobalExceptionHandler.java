package com.innogrid.aims.common.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innogrid.aims.common.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ApiResponse<Void>> handleUpstreamError(RestClientResponseException ex) {
        String message = extractUpstreamMessage(ex);
        int upstream = ex.getStatusCode().value();
        log.warn("Upstream error {}: {}", upstream, message);

        HttpStatus status = upstream >= 500
                ? HttpStatus.BAD_GATEWAY
                : HttpStatus.valueOf(upstream);

        return ResponseEntity.status(status)
                .body(ApiResponse.error(message));
    }

    /**
     * 외부 연동 서버(Thanos·Tumblebug·Spider 등)에 연결하지 못함(연결 거부·시간 초과) — 응답 자체가 없으므로 503.
     * 응답이 온 오류는 {@link #handleUpstreamError} 가 502 로 처리한다. 원인에는 내부 주소가 들어 있어 로그에만 남긴다.
     */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleUpstreamUnreachable(ResourceAccessException ex) {
        log.warn("Upstream unreachable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("외부 연동 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."));
    }

    private static String extractUpstreamMessage(RestClientResponseException ex) {
        try {
            String body = ex.getResponseBodyAsString();
            if (!body.isBlank()) {
                JsonNode node = mapper.readTree(body);
                if (node.has("message")) {
                    return node.get("message").asText();
                }
                return body.length() > 500 ? body.substring(0, 500) : body;
            }
        } catch (Exception ignored) {}
        return ex.getStatusText();
    }
}
