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
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 랙실장도 대시보드 API (MDC 전용).
 * [요구사항 #36] 랙실장도 대시보드 — MDC별 랙 단위 장비 실장 현황 도식화
 */
@Tag(name = "랙실장도", description = "랙 단위 장비 실장 현황")
@RestController
@RequestMapping("/api/v1/racks")
public class RackController {

    private final RackRepository rackRepository;
    private final RackUnitRepository rackUnitRepository;

    public RackController(RackRepository rackRepository, RackUnitRepository rackUnitRepository) {
        this.rackRepository = rackRepository;
        this.rackUnitRepository = rackUnitRepository;
    }

    /** 랙 목록 조회 */
    @GetMapping
    public ApiResponse<List<Rack>> listRacks(@RequestParam(required = false) String mdcId) {
        List<Rack> racks;
        if (mdcId != null) {
            racks = rackRepository.findByMdcId(mdcId);
        } else {
            racks = rackRepository.findAll();
        }
        return ApiResponse.ok(racks);
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
