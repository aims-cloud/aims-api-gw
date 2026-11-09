package com.innogrid.aims.infra.controller;

import java.util.List;
import java.util.Map;

import com.innogrid.aims.bmc.UnitHardware;
import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.log.PortalLog;
import com.innogrid.aims.infra.entity.Rack;
import com.innogrid.aims.infra.service.RackService;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 랙실장도 대시보드 API (MDC 전용) — 조회 전용.
 * [요구사항 #36] 랙실장도 대시보드 — MDC별 랙 단위 장비 실장 현황 도식화
 * 랙/실장 데이터의 원본은 1세부 하드웨어 데이터(자산 수신 시 동기화)이며, 포털에서 생성/편집하지 않는다.
 *
 * 포털 로그(docs/08 §10.1 묶음 7): 전부 조회 API 라 {@code @PortalLog(ACCESS)} 로 열람 기록을 남긴다(랙 단위는 ref = RACK).
 */
@Tag(name = "랙실장도", description = "랙 단위 장비 실장 현황 (2D/3D 뷰)")
@RestController
@RequestMapping("/api/v1/racks")
public class RackController {

    private final RackService rackService;

    public RackController(RackService rackService) {
        this.rackService = rackService;
    }

    /** 랙 목록 조회 — 분류/장소/층/구역/열/위치 필터 지원 */
    @Operation(summary = "랙 목록", description = "category/location/floorNo/zoneName/rowNo/positionNo 필터")
    @PortalLog(type = PortalLogType.ACCESS, screen = "랙실장도", action = "조회",
               content = "'랙 목록 조회 (MDC: ' + (#mdcId ?: '전체') + ', 분류: ' + (#category ?: '전체') + ', 위치: ' + (#location ?: '전체') + ')'")
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
    @PortalLog(type = PortalLogType.ACCESS, screen = "랙실장도", action = "조회",
               content = "'랙 실장 현황 조회: 랙 id ' + #rackId",
               refType = "RACK", refId = "#rackId")
    @GetMapping("/{rackId}/layout")
    public ApiResponse<Map<String, Object>> getLayout(@PathVariable Long rackId) {
        return ApiResponse.ok(rackService.getLayout(rackId));
    }

    /** 실장 장비 하드웨어 상세 — BMC(Redfish) 실시간 조회, 미도달 시 최근 스냅샷 폴백 (장비 클릭 시) */
    @Operation(summary = "실장 장비 하드웨어 상세 (Redfish)",
            description = "BMC 연동 장비의 모델/시리얼/CPU/메모리/전원상태. BMC 실시간 조회 성공 시 live=true 이며 "
                    + "스냅샷을 갱신하고, 미도달 시 마지막 저장 스냅샷(live=false, collectedAt)을 반환. 미연동 장비는 data=null")
    @PortalLog(type = PortalLogType.ACCESS, screen = "랙실장도", action = "조회",
               content = "'랙 유닛 장비 조회: 랙 id ' + #rackId + ' (유닛 ' + #unitId + ')'",
               refType = "RACK", refId = "#rackId")
    @GetMapping("/{rackId}/units/{unitId}/hardware")
    public ApiResponse<UnitHardware> getUnitHardware(@PathVariable Long rackId,
                                                     @PathVariable Long unitId) {
        return ApiResponse.ok(rackService.getUnitHardware(rackId, unitId));
    }
}
