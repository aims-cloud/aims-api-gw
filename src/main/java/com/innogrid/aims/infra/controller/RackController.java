package com.innogrid.aims.infra.controller;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.mock.MockDataProvider;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 랙실장도 대시보드 API (MDC 전용).
 * [요구사항 #36] 랙실장도 대시보드 — MDC별 랙 단위 장비 실장 현황 도식화
 */
@Tag(name = "랙실장도", description = "랙 단위 장비 실장 현황")
@RestController
@RequestMapping("/api/v1/racks")
public class RackController {

    private final MockDataProvider mock;

    public RackController(MockDataProvider mock) {
        this.mock = mock;
    }

    /** 랙 목록 조회 */
    @GetMapping
    public ApiResponse<?> listRacks(@RequestParam(required = false) String mdcId) {
        return ApiResponse.ok(mock.getRacks());
    }

    /** 랙 상세 (실장 장비 정보 포함) */
    @GetMapping("/{rackId}")
    public ApiResponse<?> getRackLayout(@PathVariable Long rackId) {
        return ApiResponse.ok(mock.getRacks().get(0));
    }

    /** 랙 등록 */
    @PostMapping
    public ApiResponse<?> createRack(@RequestBody Object request) {
        return ApiResponse.ok(null);
    }

    /** 랙에 장비 배치 */
    @PostMapping("/{rackId}/units")
    public ApiResponse<?> assignUnitToRack(@PathVariable Long rackId,
                                           @RequestBody Object request) {
        return ApiResponse.ok(null);
    }
}
