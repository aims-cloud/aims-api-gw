package com.innogrid.aims.infra.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

import com.innogrid.aims.bmc.BmcHardwareSnapshot;
import com.innogrid.aims.bmc.BmcHardwareSnapshotRepository;
import com.innogrid.aims.bmc.BmcProperties;
import com.innogrid.aims.bmc.RedfishClient;
import com.innogrid.aims.bmc.RedfishSystem;
import com.innogrid.aims.bmc.UnitHardware;
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
    private final BmcProperties bmcProperties;
    private final RedfishClient redfishClient;
    private final BmcHardwareSnapshotRepository snapshotRepository;

    public RackService(RackRepository rackRepository, RackUnitRepository rackUnitRepository,
                       BmcProperties bmcProperties, RedfishClient redfishClient,
                       BmcHardwareSnapshotRepository snapshotRepository) {
        this.rackRepository = rackRepository;
        this.rackUnitRepository = rackUnitRepository;
        this.bmcProperties = bmcProperties;
        this.redfishClient = redfishClient;
        this.snapshotRepository = snapshotRepository;
    }

    /**
     * 실장 장비 하드웨어 상세.
     * BMC 에 실시간으로 닿으면 Redfish 조회 후 스냅샷을 갱신(live=true)하고,
     * 닿지 못하면 마지막으로 저장된 스냅샷(live=false)을 내린다.
     * BMC 미연동 장비이거나 스냅샷도 없으면 null.
     */
    @org.springframework.transaction.annotation.Transactional
    public UnitHardware getUnitHardware(Long rackId, Long unitId) {
        RackUnit unit = rackUnitRepository.findById(unitId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "실장 장비를 찾을 수 없습니다: " + unitId));
        if (unit.getRack() == null || !rackId.equals(unit.getRack().getId())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "해당 랙의 장비가 아닙니다: " + unitId);
        }
        String endpointId = unit.getBmcEndpointId();
        if (endpointId == null || endpointId.isBlank()) return null;

        // 1) 실시간 조회 시도
        RedfishSystem live = redfishClient.getSystem(bmcProperties.find(endpointId));
        if (live != null) {
            OffsetDateTime now = OffsetDateTime.now();
            BmcHardwareSnapshot snap = snapshotRepository.findById(endpointId)
                    .orElseGet(() -> new BmcHardwareSnapshot(endpointId));
            snap.apply(live, now);
            snapshotRepository.save(snap);          // 최신값으로 스냅샷 갱신
            return UnitHardware.live(live, now);
        }

        // 2) 실패 시 저장된 스냅샷 폴백
        return snapshotRepository.findById(endpointId)
                .map(s -> UnitHardware.cached(s.toRedfishSystem(), s.getCollectedAt()))
                .orElse(null);
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
                slot.put("unitId", occupant.getId());
                slot.put("assetName", occupant.getAssetName());
                slot.put("assetType", occupant.getAssetType());
                // BMC 연동 장비면 식별자를 내려 프론트가 하드웨어 상세조회 가능 여부를 판단
                slot.put("bmcEndpointId", occupant.getBmcEndpointId());
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
