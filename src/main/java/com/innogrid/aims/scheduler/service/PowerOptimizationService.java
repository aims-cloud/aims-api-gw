package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PowerOptimizationResponse;
import com.innogrid.aims.scheduler.dto.PowerOptimizationResponse.Cluster;
import com.innogrid.aims.scheduler.dto.PowerOptimizationResponse.Finding;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.model.NodeResource;

/**
 * 클러스터 전력 최적화 진단.
 *
 * <p>노드 하나의 효율은 {@link PowerEfficiencyService} 가 답한다. 이 서비스는 한 층 위에서
 * <b>클러스터 전체로 무엇을 할 수 있는지</b>를 답한다 — 몇 대가 놀고 있고 얼마를 반납할 수 있는가.
 * PR ② 에서 만든 {@code reclaimableIdleWatts} 가 배치 근거 문자열에만 찍히고 있었는데, 이 진단이
 * 그 값의 출구다.</p>
 *
 * <p>새 수식을 만들지 않는다. {@code PowerModel} 과 {@code PlacementContext} 가 이미 가진 계산을
 * 조합해 사람이 읽을 수 있는 진단으로 바꾼다.</p>
 */
@Service
public class PowerOptimizationService {

    /** 이상 전력 곡선을 이 비율 이상 넘기면 낭비로 본다. */
    private static final double WASTE_THRESHOLD = 0.8;

    /** 전력 여유가 이보다 적으면 추가 배치가 어렵다고 본다(%). */
    private static final double LOW_HEADROOM_PERCENT = 20.0;

    static final String NO_POWER_METRIC =
            "전력 메트릭이 없어 최적화 진단을 낼 수 없습니다. IPMI exporter 연동을 확인하세요";

    private final SchedulingService schedulingService;
    private final PowerModel powerModel;
    private final SchedulerProperties.Power props;

    public PowerOptimizationService(SchedulingService schedulingService,
                                    PowerModel powerModel,
                                    SchedulerProperties properties) {
        this.schedulingService = schedulingService;
        this.powerModel = powerModel;
        this.props = properties.getPower();
    }

    /** 현재 노드 상태로 진단한다. */
    public PowerOptimizationResponse diagnoseCurrent() {
        return diagnose(schedulingService.getNodeResources());
    }

    /**
     * 주어진 노드 스냅샷으로 진단한다.
     * 테스트가 합성 클러스터를 넣을 수 있도록 열어 둔다 — 배포 환경은 노드가 1대뿐이라
     * 여러 노드가 섞인 판단을 실환경에서 검증할 수 없다.
     */
    public PowerOptimizationResponse diagnose(List<NodeResource> nodes) {
        OffsetDateTime now = OffsetDateTime.now();
        List<NodeResource> safe = nodes == null ? List.of() : nodes;

        boolean measured = safe.stream().anyMatch(this::hasPower);
        Cluster cluster = cluster(safe, measured);

        // 전력을 하나도 수집하지 못했으면 진단할 근거가 없다. 빈 배열과 사유를 돌려준다 —
        // 0 건이라고 "문제 없음" 으로 읽히면 안 되므로 사유를 클러스터에 함께 담는다.
        List<Finding> findings = measured ? findings(safe) : List.of();

        double saving = findings.stream()
                .map(Finding::estimatedSavingWatts)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).sum();

        return new PowerOptimizationResponse(now, cluster, findings,
                new PowerOptimizationResponse.Summary(
                        findings.size(), round(saving), percentOf(saving, cluster.totalPowerWatts())));
    }

    // ── 클러스터 현황 ───────────────────────────────────────────

    private Cluster cluster(List<NodeResource> nodes, boolean measured) {
        int active = (int) nodes.stream().filter(powerModel::isActive).count();
        double total = nodes.stream().mapToDouble(NodeResource::getPowerWatts).sum();
        double reclaimable = nodes.stream()
                .filter(n -> !powerModel.isActive(n))
                .mapToDouble(powerModel::idlePower).sum();

        return new Cluster(nodes.size(), active, nodes.size() - active,
                round(total), round(measured ? reclaimable : 0),
                measured, measured ? null : NO_POWER_METRIC);
    }

    // ── 진단 항목 ──────────────────────────────────────────────

    private List<Finding> findings(List<NodeResource> nodes) {
        List<Finding> found = new ArrayList<>();
        for (NodeResource node : nodes) {
            if (!hasPower(node)) continue;

            idleNode(node).ifPresent(found::add);
            powerWaste(node).ifPresent(found::add);
            lowHeadroom(node).ifPresent(found::add);
        }
        // 절감이 큰 항목이 위로 오게 한다. 화면이 무엇부터 봐야 할지 정해 준다.
        found.sort(Comparator.comparingDouble(
                (Finding f) -> f.estimatedSavingWatts() == null ? 0 : f.estimatedSavingWatts()).reversed());
        return found;
    }

    /** 놀고 있는데 유휴 전력을 계속 쓰는 노드. 반납하면 그 전력을 통째로 아낀다. */
    private java.util.Optional<Finding> idleNode(NodeResource node) {
        if (powerModel.isActive(node)) return java.util.Optional.empty();

        double idle = powerModel.idlePower(node);
        return java.util.Optional.of(new Finding(
                "IDLE_NODE", "HIGH", node.getMdcId(), node.getNodeId(),
                "유휴 노드가 전력을 소모합니다",
                String.format("CPU %.1f%% 로 사실상 비어 있으나 %.0fW 를 소모합니다",
                        node.getCpuUsagePercent(), node.getPowerWatts()),
                round(idle),
                "워크로드를 통합하고 노드를 반납하세요"));
    }

    /** 부하에 비해 전력을 과하게 쓰는 노드. 이상 곡선을 넘긴 만큼이 낭비다. */
    private java.util.Optional<Finding> powerWaste(NodeResource node) {
        double waste = powerModel.wasteScore(node);
        if (waste >= WASTE_THRESHOLD) return java.util.Optional.empty();

        double over = (1.0 - waste) * node.getPowerCapacityWatts();
        return java.util.Optional.of(new Finding(
                "POWER_WASTE", over > node.getPowerCapacityWatts() * 0.3 ? "HIGH" : "MEDIUM",
                node.getMdcId(), node.getNodeId(),
                "부하에 비해 전력을 많이 씁니다",
                String.format("CPU %.1f%% 인데 %.0fW 를 소모해 이상 곡선을 %.0fW 넘겼습니다",
                        node.getCpuUsagePercent(), node.getPowerWatts(), over),
                round(over),
                "전원 설정과 유휴 전력을 점검하세요"));
    }

    /**
     * 전력 여유가 부족한 노드.
     * 절감 항목이 아니라 <b>배치 제약</b>이라 {@code estimatedSavingWatts} 를 비운다 —
     * 여기서 숫자를 채우면 절감 합계가 부풀려진다.
     */
    private java.util.Optional<Finding> lowHeadroom(NodeResource node) {
        double headroom = node.powerHeadroomPercent();
        if (headroom >= LOW_HEADROOM_PERCENT) return java.util.Optional.empty();

        return java.util.Optional.of(new Finding(
                "LOW_HEADROOM", "HIGH", node.getMdcId(), node.getNodeId(),
                "전력 여유가 부족합니다",
                String.format("용량 %.0fW 중 %.0fW 를 써 여유가 %.1f%% 뿐입니다",
                        node.getPowerCapacityWatts(), node.getPowerWatts(), headroom),
                null,
                "추가 배치를 제한하고 워크로드를 분산하세요"));
    }

    // ── 공통 ───────────────────────────────────────────────────

    boolean hasPower(NodeResource node) {
        return node.getPowerWatts() > 0 && node.getPowerCapacityWatts() > 0;
    }

    /** 총 전력이 0 이면 비율을 낼 수 없다. 0% 로 채우면 "절감 없음" 으로 읽힌다. */
    static Double percentOf(double value, double total) {
        if (total <= 0) return null;
        return round(value / total * 100);
    }

    static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    SchedulerProperties.Power props() {
        return props;
    }

    SchedulingService schedulingService() {
        return schedulingService;
    }

    PowerModel powerModel() {
        return powerModel;
    }
}
