package com.innogrid.aims.scheduler.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.constraint.Exclusion;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

/**
 * 세 엔진이 같이 쓰는 채점 흐름 — 빈 후보 실패 → 노드마다 제외 판정 뒤 채점 → 적격 후보 중 최고점 선택 → 총점 내림차순 정렬 →
 * 엔진별 후처리 → 실패 사유 또는 선택 사유.
 *
 * <p>엔진은 채점({@link #scoreNode})과 사유 문장만 맡는다. 다음은 바꾸면 결과가 달라지므로 이 클래스가 고정한다.</p>
 * <ul>
 *   <li>제외 판정은 노드마다 채점 직전에 한 번 부른다 — 임계 온도를 넘은 노드에 냉각 기록을 남기는 부작용이 있어 순서와 횟수가 결과다.</li>
 *   <li>최고점은 적격 후보만, 엄격한 초과로 고른다 — 동점이면 입력 순서가 앞인 노드가 남는다. 정렬은 안정 정렬이라 동점 순서도 입력 순서다.</li>
 *   <li>호출 한 번 동안만 살아야 할 값(계측 집계·평균 온도·승자의 계측 여부)은 {@link Run} 에 두고, 최고점은 {@code schedule} 의
 *       지역 변수다. 엔진은 싱글턴 빈이라 인스턴스 필드에 두면 앞선 호출이 다음 결과에 섞인다. 이 클래스에는 인스턴스 필드가 없다.</li>
 *   <li>{@code details} 는 엔진이 만든 {@code LinkedHashMap} 을 그대로 쓴다 — 키 순서가 응답 바이트이고, 전력 엔진 후처리가 사후에
 *       키를 더하므로 복사하거나 변경 불가 맵으로 감싸지 않는다.</li>
 * </ul>
 *
 * @param <R> 호출당 누적값
 */
public abstract class AbstractScoringEngine<R extends AbstractScoringEngine.Run> implements SchedulingEngine {

    /** 호출 한 번의 누적값. 엔진 필드에 두지 않는다. */
    protected static class Run {
        /** 적격 최고점이 바뀔 때마다 부른다 — 승자의 성질을 함께 기록할 때 쓴다. */
        protected void bestChanged(NodeResource node, NodeScore best) {}
    }

    protected AbstractScoringEngine() {}

    @Override
    public final SchedulingResult schedule(Workload workload, PlacementContext context) {
        List<NodeResource> candidates = context.nodes();
        if (candidates.isEmpty()) {
            return SchedulingResult.failure("후보 노드가 없습니다");
        }

        R run = newRun(workload, context);
        List<NodeScore> scores = new ArrayList<>();
        NodeScore bestScore = null;

        for (NodeResource node : candidates) {
            Exclusion exclusion = context.exclusion(workload, node);
            NodeScore ns = scoreNode(workload, context, node, exclusion, run);
            scores.add(ns);

            if (exclusion == null && (bestScore == null || ns.totalScore() > bestScore.totalScore())) {
                bestScore = ns;
                run.bestChanged(node, ns);
            }
        }

        // 점수 내림차순 정렬
        scores.sort(Comparator.comparingDouble(NodeScore::totalScore).reversed());
        afterRanking(scores);

        if (bestScore == null) {
            return new SchedulingResult(false, null, null, 0, noEligibleReason(), scores);
        }

        return new SchedulingResult(true, bestScore.mdcId(), bestScore.nodeId(),
                bestScore.totalScore(), reason(bestScore, context, candidates.size(), run), scores);
    }

    /** 호출 한 번의 누적값을 만든다. 후보 전체를 먼저 보는 값(평균 온도·계측 대수)은 여기서 계산한다. */
    protected abstract R newRun(Workload workload, PlacementContext context);

    /**
     * 노드 하나를 채점한다. 제외 판정은 이미 끝났고 {@code exclusion} 이 null 이면 적격이다.
     * 반올림은 엔진이 지금 자리에서 그대로 하고, 상세 키는 지금 순서 그대로 넣는다.
     */
    protected abstract NodeScore scoreNode(Workload workload, PlacementContext context, NodeResource node,
                                           Exclusion exclusion, R run);

    /** 적격 후보가 하나도 없을 때의 사유 문장. */
    protected abstract String noEligibleReason();

    /** 선택 근거 한 줄. */
    protected abstract String reason(NodeScore best, PlacementContext context, int candidateCount, R run);

    /** 정렬 뒤·사유 앞에 부르는 후처리. 기본은 아무것도 하지 않는다. */
    protected void afterRanking(List<NodeScore> ranked) {}

    /** 적격 여부·제외 문구·제외 코드를 제외 판정 하나에서 만든다. 값은 엔진이 이미 반올림한 것을 그대로 받는다. */
    protected static NodeScore nodeScore(NodeResource node, double cpu, double mem, double power, double temperature,
                                         double network, double total, Exclusion exclusion, Map<String, Double> details) {
        return new NodeScore(node.getMdcId(), node.getNodeId(), cpu, mem, power, temperature, network, total,
                exclusion == null, Exclusion.messageOf(exclusion), Exclusion.reasonOf(exclusion), details);
    }

    /** 디스크 요구가 있을 때만 디스크 검사 키를 넣는다 — 요구가 없는 배치의 키 집합은 그대로. 부르는 자리는 엔진마다 지금 그대로. */
    protected static void putDiskCheck(Map<String, Double> details, Workload workload, NodeResource node) {
        if (ConstraintEvaluator.diskCheckRelevant(workload)) {
            details.put(ConstraintEvaluator.DISK_CHECK_DETAIL, ConstraintEvaluator.diskCheckApplied(workload, node) ? 1.0 : 0.0);
        }
    }
}
