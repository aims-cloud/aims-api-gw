package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 서버별 전력 효율성 평가 결과.
 *
 * <p>지표를 낼 수 없는 노드는 {@code evaluable=false} 이고 파생 값이 전부 {@code null} 이다.
 * 0 이나 0.5 같은 숫자를 채우지 않는 이유는, 판단 근거가 없다는 사실 자체가 정보이기
 * 때문이다. IPMI 가 연동되지 않은 환경에서는 모든 노드가 이 상태로 나오는 것이 정상이다.</p>
 *
 * <p>시각은 UTC 다. 표출 변환은 프런트가 한다.</p>
 */
public record PowerEfficiencyResponse(
        OffsetDateTime evaluatedAt,
        Summary summary,
        List<NodeEfficiency> nodes
) {
    public PowerEfficiencyResponse {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /**
     * 클러스터 요약.
     *
     * @param averagePeiRaw          평가 가능 노드만의 평균. 하나도 없으면 null
     * @param minPeiRaw              평가 가능 노드 중 가장 낮은 PEI 원값(%/W). 평가 가능 노드가 없으면 null
     * @param maxPeiRaw              평가 가능 노드 중 가장 높은 PEI 원값. 상대 등급 A 가 "좋다"가 아니라 "클러스터 안에서 상대적으로
     *                               낫다"라는 뜻임을 화면이 절대 수준과 함께 보여 주기 위한 값(12장 T1 → 단계 13)
     * @param gradeAvailable         상대 등급을 매길 만큼 표본이 있는지
     * @param gradeUnavailableReason 매길 수 없을 때의 사유. 화면에 그대로 띄운다
     */
    @Schema(name = "PowerEfficiencySummary")
    public record Summary(
            int totalNodes,
            int evaluableNodes,
            Double averagePeiRaw,
            Double minPeiRaw,
            Double maxPeiRaw,
            double totalPowerWatts,
            double totalCapacityWatts,
            boolean gradeAvailable,
            String gradeUnavailableReason
    ) {}

    /**
     * 노드 하나의 평가.
     *
     * @param peiRaw          전력 효율 지표 원값(%/W) — CPU 사용률 ÷ 소비 전력
     * @param peiNormalized   0~1 로 정규화한 값
     * @param proportionality 이상 전력 곡선과의 근접도(0~1). 1 에 가까울수록 부하에 비례해 전력을 쓴다
     * @param grade           클러스터 내 4분위 상대 등급 A~D. 표본이 모자라면 null
     * @param rank            PEI 내림차순 순위. 평가 불가 노드는 null
     * @param recommendation  운영 권고 한 줄. 해당 없으면 null
     */
    @Schema(name = "PowerEfficiencyNodeEfficiency")
    public record NodeEfficiency(
            String mdcId,
            String nodeId,
            boolean evaluable,

            double cpuUsagePercent,
            double powerWatts,
            double powerCapacityWatts,

            Double idlePowerWatts,
            Double headroomPercent,
            Double peiRaw,
            Double peiNormalized,
            Double proportionality,

            String grade,
            Integer rank,
            String powerSource,
            String recommendation
    ) {
        /** 순위·등급을 채운 사본. 레코드라 새로 만들어 돌려준다. */
        public NodeEfficiency ranked(Integer rank, String grade) {
            return new NodeEfficiency(mdcId, nodeId, evaluable,
                    cpuUsagePercent, powerWatts, powerCapacityWatts,
                    idlePowerWatts, headroomPercent, peiRaw, peiNormalized, proportionality,
                    grade, rank, powerSource, recommendation);
        }

        /** 권고를 채운 사본. */
        public NodeEfficiency advised(String recommendation) {
            return new NodeEfficiency(mdcId, nodeId, evaluable,
                    cpuUsagePercent, powerWatts, powerCapacityWatts,
                    idlePowerWatts, headroomPercent, peiRaw, peiNormalized, proportionality,
                    grade, rank, powerSource, recommendation);
        }
    }
}
