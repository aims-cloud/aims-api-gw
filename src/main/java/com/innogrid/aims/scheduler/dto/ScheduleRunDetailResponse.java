package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 실행 1회의 요약과 워크로드별 배치 결정 목록.
 *
 * <p>명세의 제약 조건 기반 배치 항목이 무엇을 근거로 어느 노드를 골랐는지 보이려면 결정 단위 기록이 필요하다.
 * 화면에는 아직 이 항목이 없다.</p>
 */
@Schema(description = "실행 1회의 요약과 워크로드별 배치 결정 목록")
public record ScheduleRunDetailResponse(
        @Schema(description = "실행 요약(실행 이력 1행과 같은 형태)")
        ScheduleRunResponse run,
        @Schema(description = "이 실행 전후의 클러스터 상태. 이 열이 생기기 전 실행이면 null")
        ClusterSnapshot clusterSnapshot,
        @Schema(description = "이 실행이 내린 워크로드별 배치 결정, 결정 시각 오름차순")
        List<Decision> decisions
) {
    public ScheduleRunDetailResponse {
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    /**
     * 실행 1회가 클러스터를 <b>무엇으로 바꿨는가</b>.
     *
     * <p>실행 이력 목록(1행 응답)에는 싣지 않는다. 목록은 한 화면에 수십 행이 뜨는 자리라 행마다 열 12개를
     * 더하면 프런트가 쓰지도 않는 값으로 응답이 부풀고, 이 숫자들은 <b>한 실행을 들여다볼 때</b> 뜻이 있다.</p>
     *
     * <p>축이 둘로 갈린다. 계측 노드 수와 실측 전력 합은 배치를 얹어도 다시 재지 않으므로 전·후가 없고,
     * 가동 노드 수·회수 가능 전력·평균 온도는 예약을 반영한 실효값이라 전·후가 따로 있다. 여기서 "후"는
     * 재측정이 아니라 <b>이번 실행의 예약을 반영한 값</b>이다 — 설비가 반응하기 전이라 다시 재도 같은 숫자다.</p>
     */
    @Schema(description = "실행 전후 클러스터 상태. 배치를 얹어도 변하지 않는 축은 한 번만, 예약에 반응하는 축은 전·후로 싣는다")
    public record ClusterSnapshot(
            @Schema(description = "기록 상태. OK 는 전·후 모두, BEFORE_ONLY 는 전을 적은 뒤 후를 적기 전에 프로세스가 "
                    + "내려간 회차, NO_TARGET 은 배치할 워크로드가 없어 재기 전에 끝난 평범한 회차, "
                    + "NO_NODES 는 노드를 한 대도 모으지 못한 장애 회차, FAILED 는 재다 오류가 난 회차")
            ScheduleRun.SnapshotStatus status,
            @Schema(description = "이 회차의 숫자를 정의한 설정 노브의 지문. 값이 다른 구간은 같은 열이라도 정의가 달라 "
                    + "한 축으로 묶어 집계하면 안 된다", example = "3f2a91c0d4e7")
            String knobsDigest,
            @Schema(description = "배치 후보에 오른 노드 수")
            Integer nodeCount,
            @Schema(description = "메트릭을 못 얻어 수집 단계에서 빠진 노드 수. 제약으로 걸러진 수가 아니다")
            Integer excludedCount,
            @Schema(description = "전력을 실측한 노드 수. 0 이면 아래 전력 값들이 근거 없음이라는 뜻이다")
            Integer powerMeasuredNodes,
            @Schema(description = "실측 전력 합(W). 실측 노드가 없으면 null")
            Double measuredPowerWatts,
            @Schema(description = "실행 전 가동 중이던 노드 수(실효 CPU 기준)")
            Integer activeNodesBefore,
            @Schema(description = "이번 실행이 잡은 예약을 반영한 가동 노드 수. 실행 계층이 받지 않은 배치의 예약은 "
                    + "같은 실행 안에서 되돌리므로 여기에 남지 않는다")
            Integer activeNodesAfter,
            @Schema(description = "실행 전 비어 있던 노드의 유휴 전력 합(W). 실측 노드가 없으면 null. "
                    + "계측 노드는 실측에서 역산하고 미계측 노드는 정격으로 어림하므로 둘이 섞인 판에서는 추정이 함께 더해진다")
            Double reclaimableWattsBefore,
            @Schema(description = "예약을 반영한 회수 가능 전력(W). 줄어든 만큼이 이번 배치가 잡은 여유다")
            Double reclaimableWattsAfter,
            @Schema(description = "실행 전 평균 실효 온도(℃). 온도를 잰 노드가 없으면 null")
            Double avgTemperatureBefore,
            @Schema(description = "예약을 반영한 평균 실효 온도(℃). 계측이 아니라 열관성 계수로 낸 외삽이라, "
                    + "전·후 차는 온도 × 계수 × 예약 CPU 비율로 완전히 결정되고 온도계 관측을 포함하지 않는다")
            Double avgTemperatureAfter
    ) {
        /** 기록이 없는 회차는 통째로 null이다 — 열마다 0을 내리면 "쟀는데 0"으로 읽힌다. */
        public static ClusterSnapshot from(ScheduleRun r) {
            if (r.getSnapshotStatus() == null) return null;
            return new ClusterSnapshot(
                    r.getSnapshotStatus(), r.getSnapshotKnobsDigest(),
                    r.getSnapshotNodeCount(), r.getSnapshotExcludedCount(),
                    r.getSnapshotMeasuredNodes(), r.getSnapshotMeasuredPowerWatts(),
                    r.getSnapshotActiveNodesBefore(), r.getSnapshotActiveNodesAfter(),
                    r.getSnapshotReclaimableWattsBefore(), r.getSnapshotReclaimableWattsAfter(),
                    r.getSnapshotAvgTemperatureBefore(), r.getSnapshotAvgTemperatureAfter());
        }
    }

    /**
     * 13필드 — 설명 누락과 스케줄 화면 DTO 9종의 합계는 {@code ScheduleSchemaContractTest}가, 13이라는 수와 순서(맨 끝)는
     * {@code ScheduleRunDetailDecisionApiTest}가 고정한다. {@code exclusionCodes}는 배치 이력 응답의 같은 이름 필드와 같은
     * 문자열을 그대로 싣는다 — 엔진이 배치 시점에 본 후보 제외 집계라, 실행 계층이 그 뒤 배치를 되돌린 건도 그대로 남는다.
     */
    @Schema(description = "워크로드 한 건에 대한 배치 결정 — 어느 노드를 왜 골랐는지, 또는 왜 못 골랐는지")
    public record Decision(
            @Schema(description = "배치 이력 PK")
            Long historyId,
            @Schema(description = "대상 워크로드 id")
            Long workloadId,
            @Schema(description = "대상 워크로드 이름")
            String workloadName,
            @Schema(description = "배치 성공 여부. 실행이 만든 결정이라 항상 채워진다")
            Boolean success,
            @Schema(description = "선택한 MDC id. 실패면 null")
            String selectedMdcId,
            @Schema(description = "선택한 노드 id. 실패면 null")
            String selectedNodeId,
            @Schema(description = "선택 노드의 총점. 실패면 0")
            double finalScore,
            @Schema(description = "성공이면 선택 근거, 실패면 제외 사유")
            String reason,
            @Schema(description = "이 결정에서 후보로 본 노드 수. 핵심 메트릭을 못 읽어 수집 단계에서 빠진 노드는 세지 않고, "
                    + "제약으로 걸러진 노드는 포함한다")
            int candidateCount,
            @Schema(description = "결정에 쓴 엔진 id — run.engineId 와 같은 값이다(이름이 아니라 id)")
            String engineName,
            @Schema(description = "결정에 쓴 배치 정책 이름")
            String policyName,
            @Schema(description = "결정 시각(UTC)")
            OffsetDateTime decidedAt,
            @Schema(description = "후보 노드의 제외 사유 코드 집계 — 이력 응답의 exclusionCodes 와 같은 JSON 객체 문자열(코드별 건수, "
                    + "키 오름차순, 적격 후보 미포함). 제외가 없거나 이 필드가 생기기 전의 결정이면 null. 엔진이 배치 시점에 본 집계라 "
                    + "실행 계층이 그 뒤 배치를 되돌린 건도 그대로 남는다")
            String exclusionCodes
    ) {
        public static Decision from(SchedulingHistory h) {
            return new Decision(
                    h.getId(), h.getWorkloadId(), h.getWorkloadName(),
                    h.getSuccess(), h.getSelectedMdcId(), h.getSelectedNodeId(),
                    h.getFinalScore(), h.getReason(), h.getCandidateCount(),
                    h.getEngineName(), h.getPolicyName(), h.getCreatedAt(), h.getExclusionCodes());
        }
    }
}
