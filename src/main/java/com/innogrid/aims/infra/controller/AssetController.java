package com.innogrid.aims.infra.controller;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.mock.MockDataProvider;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 물리/가상 자산 관리 API (MDC 자체 관리).
 * [요구사항 #34] 물리/가상 자산 관리 — 사양·위치·상태 등 자산 정보 통합 등록·관리
 */
@Tag(name = "자산 관리", description = "물리/가상 자산 관리")
@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private final MockDataProvider mock;

    public AssetController(MockDataProvider mock) {
        this.mock = mock;
    }

    /** 자산 목록 조회 */
    @GetMapping
    public ApiResponse<?> listAssets(@RequestParam(required = false) String mdcId,
                                     @RequestParam(required = false) String type) {
        return ApiResponse.ok(mock.getAssets());
    }

    /** 자산 등록 */
    @PostMapping
    public ApiResponse<?> createAsset(@RequestBody Object request) {
        return ApiResponse.ok(null);
    }

    /** 자산 상세 조회 */
    @GetMapping("/{assetId}")
    public ApiResponse<?> getAsset(@PathVariable Long assetId) {
        return ApiResponse.ok(mock.getAssets().get(0));
    }

    /** 자산 정보 수정 */
    @PutMapping("/{assetId}")
    public ApiResponse<?> updateAsset(@PathVariable Long assetId,
                                      @RequestBody Object request) {
        return ApiResponse.ok(null);
    }

    /** 자산 삭제 */
    @DeleteMapping("/{assetId}")
    public ApiResponse<Void> deleteAsset(@PathVariable Long assetId) {
        return ApiResponse.ok(null);
    }
}
