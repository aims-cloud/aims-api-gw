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
