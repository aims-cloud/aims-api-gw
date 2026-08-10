package com.innogrid.aims.resource.controller;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.mock.MockDataProvider;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 카탈로그 관리 (자원 요청/승인) API.
 * [요구사항 #20] 카탈로그 관리 — 사전 정의된 자원 카탈로그를 통한 자원 요청·승인·배포
 */
@Tag(name = "카탈로그", description = "자원 요청/승인")
@RestController
@RequestMapping("/api/v1/catalogs")
public class CatalogController {

    private final MockDataProvider mock;

    public CatalogController(MockDataProvider mock) {
        this.mock = mock;
    }

    /** 카탈로그 항목 목록 */
    @GetMapping
    public ApiResponse<?> listCatalogItems() {
        return ApiResponse.ok(mock.getCatalogItems());
    }

    /** 카탈로그 항목 등록 */
    @PostMapping
    public ApiResponse<?> createCatalogItem(@RequestBody Object request) {
        return ApiResponse.ok(null);
    }

    /** 카탈로그 항목 수정 */
    @PutMapping("/{catalogId}")
    public ApiResponse<?> updateCatalogItem(@PathVariable Long catalogId,
                                            @RequestBody Object request) {
        return ApiResponse.ok(null);
    }

    /** 자원 요청 (사용자) */
    @PostMapping("/{catalogId}/requests")
    public ApiResponse<?> requestResource(@PathVariable Long catalogId,
                                          @RequestBody Object request) {
        return ApiResponse.ok(null);
    }

    /** 자원 요청 목록 조회 (관리자) */
    @GetMapping("/requests")
    public ApiResponse<?> listResourceRequests(@RequestParam(required = false) String status) {
        return ApiResponse.ok(null);
    }

    /** 자원 요청 승인/반려 */
    @PostMapping("/requests/{requestId}/approve")
    public ApiResponse<?> approveRequest(@PathVariable Long requestId,
                                         @RequestParam boolean approved) {
        return ApiResponse.ok(null);
    }
}
