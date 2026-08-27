package com.innogrid.aims.infra.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.Rack;
import com.innogrid.aims.infra.entity.RackUnit;
import com.innogrid.aims.infra.repository.RackRepository;
import com.innogrid.aims.infra.repository.RackUnitRepository;

/**
 * 랙실장도 서비스.
 * - 필터 조회: 분류/장소/층/구역/열/위치
 * - 뷰 데이터: 2D(U 단위 점유 그리드) / 3D(랙 배치 좌표) 공용 레이아웃 응답
 *   랙 데이터의 원본은 1세부 하드웨어 데이터이며 자산 수신(ingest) 시 실장 위치가 동기화된다.
 */
@Service
public class RackService {

    private final RackRepository rackRepository;
    private final RackUnitRepository rackUnitRepository;

    public RackService(RackRepository rackRepository, RackUnitRepository rackUnitRepository) {
        this.rackRepository = rackRepository;
        this.rackUnitRepository = rackUnitRepository;
    }

    /** 랙 동적 필터 조회 (분류/장소/층/구역/열/위치 + mdcId). */
    public List<Rack> search(String mdcId, String category, String location,
                             String floorNo, String zoneName, String rowNo, String positionNo) {
        Specification<Rack> spec = (root, query, cb) -> cb.conjunction();

        spec = andEquals(spec, "mdcId", mdcId);
        spec = andEquals(spec, "category", category);
        spec = andEquals(spec, "location", location);
        spec = andEquals(spec, "floorNo", floorNo);
        spec = andEquals(spec, "zoneName", zoneName);
        spec = andEquals(spec, "rowNo", rowNo);
        spec = andEquals(spec, "positionNo", positionNo);

        return rackRepository.findAll(spec);
    }

    private static Specification<Rack> andEquals(Specification<Rack> spec, String field, String value) {
        if (value == null || value.isBlank()) return spec;
        return spec.and((root, query, cb) -> cb.equal(root.get(field), value));
    }

    /**
     * 랙 레이아웃 — 2D/3D 뷰 공용 데이터.
     * units: U1 ~ U{totalUnits} 전체 슬롯의 점유 현황 (하단 U1 → 상단 U{n})
     * placement: 3D 배치용 랙 위치 정보 (장소/층/구역/열/위치)
     */
    public Map<String, Object> getLayout(Long rackId) {
        Rack rack = rackRepository.findById(rackId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "랙을 찾을 수 없습니다: " + rackId));
        List<RackUnit> units = rackUnitRepository.findByRackId(rackId);

        // U 위치 → 점유 장비 매핑 ("U10", "10" 표기 모두 허용)
        Map<Integer, RackUnit> occupancy = new LinkedHashMap<>();
        for (RackUnit unit : units) {
            Integer position = parseUnitPosition(unit.getPosition());
            if (position != null) {
                occupancy.put(position, unit);
            }
        }

        List<Map<String, Object>> slots = new ArrayList<>(rack.getTotalUnits());
        int used = 0;
        for (int u = 1; u <= rack.getTotalUnits(); u++) {
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("unit", u);
            RackUnit occupant = occupancy.get(u);
            if (occupant != null) {
                slot.put("occupied", true);
                slot.put("assetName", occupant.getAssetName());
                slot.put("assetType", occupant.getAssetType());
                used++;
            } else {
                slot.put("occupied", false);
            }
            slots.add(slot);
        }

        Map<String, Object> placement = new LinkedHashMap<>();
        placement.put("category", rack.getCategory());
        placement.put("location", rack.getLocation());
        placement.put("floorNo", rack.getFloorNo());
        placement.put("zoneName", rack.getZoneName());
        placement.put("rowNo", rack.getRowNo());
        placement.put("positionNo", rack.getPositionNo());

        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("rackId", rack.getId());
        layout.put("name", rack.getName());
        layout.put("mdcId", rack.getMdcId());
        layout.put("totalUnits", rack.getTotalUnits());
        layout.put("usedUnits", used);
        layout.put("freeUnits", rack.getTotalUnits() - used);
        layout.put("powerCapacityKw", rack.getPowerCapacityKw());
        layout.put("placement", placement);
        layout.put("units", slots);
        return layout;
    }

    /** "U10", "u10", "10" 형태의 U 위치 문자열을 정수로 변환. 해석 불가 시 null. */
    static Integer parseUnitPosition(String position) {
        if (position == null || position.isBlank()) return null;
        String digits = position.trim();
        if (digits.regionMatches(true, 0, "U", 0, 1)) {
            digits = digits.substring(1);
        }
        try {
            int value = Integer.parseInt(digits.trim());
            return value >= 1 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
