package com.innogrid.aims.infra.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.Rack;
import com.innogrid.aims.infra.entity.RackUnit;
import com.innogrid.aims.infra.repository.RackRepository;
import com.innogrid.aims.infra.repository.RackUnitRepository;
import com.innogrid.aims.infra.service.RackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 랙실장도 대시보드 API (MDC 전용).
 * [요구사항 #36] 랙실장도 대시보드 — MDC별 랙 단위 장비 실장 현황 도식화
 * 랙/실장 데이터의 원본은 1세부 하드웨어 데이터 (자산 수신 시 동기화).
 */
@Tag(name = "자산 관리 > 랙실장도", description = "랙 단위 장비 실장 현황 (2D/3D 뷰)")
@RestController
@RequestMapping("/api/v1/racks")
public class RackController {

    private final RackRepository rackRepository;
    private final RackUnitRepository rackUnitRepository;
    private final RackService rackService;

    public RackController(RackRepository rackRepository,
                          RackUnitRepository rackUnitRepository,
                          RackService rackService) {
        this.rackRepository = rackRepository;
        this.rackUnitRepository = rackUnitRepository;
        this.rackService = rackService;
    }

    /** 랙 목록 조회 — 분류/장소/층/구역/열/위치 필터 지원 */
    @Operation(summary = "랙 목록", description = "category/location/floorNo/zoneName/rowNo/positionNo 필터")
    @GetMapping
    public ApiResponse<List<Rack>> listRacks(@RequestParam(required = false) String mdcId,
                                             @RequestParam(required = false) String category,
                                             @RequestParam(required = false) String location,
                                             @RequestParam(required = false) String floorNo,
                                             @RequestParam(required = false) String zoneName,
                                             @RequestParam(required = false) String rowNo,
                                             @RequestParam(required = false) String positionNo) {
        return ApiResponse.ok(rackService.search(
                mdcId, category, location, floorNo, zoneName, rowNo, positionNo));
    }

    /** 랙 레이아웃 — 2D/3D 뷰 공용 데이터 (U 슬롯 점유 그리드 + 배치 좌표) */
    @Operation(summary = "랙 레이아웃 (2D/3D 뷰)",
            description = "U1~U{n} 슬롯 점유 현황과 3D 배치용 위치 정보(placement)를 반환")
    @GetMapping("/{rackId}/layout")
    public ApiResponse<Map<String, Object>> getLayout(@PathVariable Long rackId) {
        return ApiResponse.ok(rackService.getLayout(rackId));
    }

    /** 랙 상세 (실장 장비 정보 포함) */
    @GetMapping("/{rackId}")
    public ApiResponse<Map<String, Object>> getRackLayout(@PathVariable Long rackId) {
        Rack rack = findRack(rackId);
        List<RackUnit> units = rackUnitRepository.findByRackId(rackId);

        Map<String, Object> response = new HashMap<>();
        response.put("rack", rack);
        response.put("units", units);
        return ApiResponse.ok(response);
    }

    /** 랙 등록 */
    @PostMapping
    public ApiResponse<Rack> createRack(@RequestBody Map<String, Object> request) {
        String mdcId = (String) request.get("mdcId");
        String name = (String) request.get("name");
        int totalUnits = request.containsKey("totalUnits") ? ((Number) request.get("totalUnits")).intValue() : 42;
        double powerCapacityKw = request.containsKey("powerCapacityKw") ? ((Number) request.get("powerCapacityKw")).doubleValue() : 0.0;

        Rack rack = new Rack(mdcId, name, totalUnits, powerCapacityKw);
        if (request.containsKey("category")) rack.setCategory((String) request.get("category"));
        if (request.containsKey("location")) rack.setLocation((String) request.get("location"));
        if (request.containsKey("floorNo")) rack.setFloorNo((String) request.get("floorNo"));
        if (request.containsKey("zoneName")) rack.setZoneName((String) request.get("zoneName"));
        if (request.containsKey("rowNo")) rack.setRowNo((String) request.get("rowNo"));
        if (request.containsKey("positionNo")) rack.setPositionNo((String) request.get("positionNo"));
        rackRepository.save(rack);
        return ApiResponse.ok(rack);
    }

    /** 랙에 장비 배치 */
    @PostMapping("/{rackId}/units")
    public ApiResponse<RackUnit> assignUnitToRack(@PathVariable Long rackId,
                                                   @RequestBody Map<String, Object> request) {
        Rack rack = findRack(rackId);

        String position = (String) request.get("position");
        String assetName = (String) request.get("assetName");
        String assetType = (String) request.get("assetType");

        RackUnit unit = new RackUnit(rack, position, assetName, assetType);
        rackUnitRepository.save(unit);
        return ApiResponse.ok(unit);
    }

    private Rack findRack(Long id) {
        return rackRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "랙을 찾을 수 없습니다"));
    }
}
