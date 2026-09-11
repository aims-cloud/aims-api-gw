package com.innogrid.aims.bmc;

import java.time.OffsetDateTime;

/**
 * 랙 실장 장비 하드웨어 조회 응답.
 * BMC 에 실시간으로 닿았는지(live)와 데이터 수집 시각(collectedAt)을 함께 내려,
 * 프론트가 "실시간/최근값" 여부를 표시할 수 있게 한다.
 *
 * @param live        true=BMC 실시간 조회 성공, false=저장된 스냅샷(폴백)
 * @param collectedAt 이 데이터를 수집한 시각 (live=true 면 방금, false 면 마지막 성공 시각)
 * @param hardware    정규화된 하드웨어 정보
 */
public record UnitHardware(
        boolean live,
        OffsetDateTime collectedAt,
        RedfishSystem hardware
) {
    public static UnitHardware live(RedfishSystem hw, OffsetDateTime at) {
        return new UnitHardware(true, at, hw);
    }

    public static UnitHardware cached(RedfishSystem hw, OffsetDateTime at) {
        return new UnitHardware(false, at, hw);
    }
}
