package com.innogrid.aims.scheduler.service;

import static org.mockito.Mockito.mock;

import java.util.List;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.repository.MdcLinkRepository;
import com.innogrid.aims.scheduler.repository.MdcPowerBudgetRepository;
import com.innogrid.aims.scheduler.repository.NodePlacementHoldRepository;
import com.innogrid.aims.scheduler.repository.PlacementInputRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

/**
 * 테스트가 {@link SchedulingService}를 조립하는 자리 — <b>테스트 소스 전용</b>.
 *
 * <p>왜 만들었나: 이 서비스는 원장이 하나 늘 때마다 생성자 인자가 하나씩 늘어난다. 테스트들이 위치 인자로
 * 직접 조립하고 있어서, 인자를 늘리면 테스트가 전부 깨진다. 그래서 "옛 인자 수 생성자"를 하나씩 남겨 왔고
 * 그 결과 생성자가 7·8·9·10인자 <b>4단</b>이 됐다. 인자 열 개가 대부분 리포지토리·서비스 타입이라
 * <b>순서를 바꿔 넣어도 컴파일되는 조합</b>이 생기는 것이 진짜 위험이었다.</p>
 *
 * <p>조립을 이 빌더에 모아 {@link SchedulingService}의 생성자를 <b>하나</b>로 둔다. 앞으로 원장이 더 붙어도
 * 고칠 곳은 이 빌더 하나다 — 테스트 호출부는 자기가 신경 쓰는 것만 이름으로 지정한다.</p>
 *
 * <p>지정하지 않은 협력자는 목(mock)이나 기본 구현으로 채운다. <b>선택 원장 셋(링크·디스패치·예산)은
 * 지정하지 않으면 {@code null}로 남고</b>, 그것이 곧 "그 기능 없음"이다 — 옛 7·8·9인자 생성자가
 * 표현하던 것과 같은 의미다. 단건 배치 입력 저장도 {@link #placementInputs}로 붙이지 않으면 꺼져 있다.</p>
 */
public final class SchedulingServiceBuilder {

    private WorkloadRepository workloads;
    private SchedulingPolicyRepository policies;
    private NodeResourceCollector collector;
    private PlacementService placement;
    private EngineRegistry engines;
    private ConstraintEvaluator evaluator;
    private SchedulerProperties properties;

    // ── 선택 원장 — 지정하지 않으면 null이고 그 기능이 꺼진다 ──
    private MdcLinkRepository links;
    private PlacementDispatchService dispatch;
    private MdcPowerBudgetRepository budgets;
    private NodePlacementHoldRepository holds;
    private PlacementInputRepository placementInputs;
    private ThermalModel thermalModel;

    private SchedulingServiceBuilder() {}

    public static SchedulingServiceBuilder aSchedulingService() {
        return new SchedulingServiceBuilder();
    }

    public SchedulingServiceBuilder workloads(WorkloadRepository v) { this.workloads = v; return this; }
    public SchedulingServiceBuilder policies(SchedulingPolicyRepository v) { this.policies = v; return this; }
    public SchedulingServiceBuilder collector(NodeResourceCollector v) { this.collector = v; return this; }
    public SchedulingServiceBuilder placement(PlacementService v) { this.placement = v; return this; }
    public SchedulingServiceBuilder engines(EngineRegistry v) { this.engines = v; return this; }
    public SchedulingServiceBuilder evaluator(ConstraintEvaluator v) { this.evaluator = v; return this; }
    public SchedulingServiceBuilder properties(SchedulerProperties v) { this.properties = v; return this; }

    /** MDC 링크 원장을 붙인다. 붙이지 않으면 링크 스냅샷은 항상 비어 있다. */
    public SchedulingServiceBuilder links(MdcLinkRepository v) { this.links = v; return this; }

    /** 실행 계층 연동을 붙인다. 붙이지 않으면 결정만 하고 넘기지 않는다. */
    public SchedulingServiceBuilder dispatch(PlacementDispatchService v) { this.dispatch = v; return this; }

    /** MDC 전력 예산 원장을 붙인다. 붙이지 않으면 MDC 총량 제약이 걸리지 않는다. */
    public SchedulingServiceBuilder budgets(MdcPowerBudgetRepository v) { this.budgets = v; return this; }

    /** 노드 배치 중지 원장을 붙인다. 붙이지 않으면 배치 중지 제약이 걸리지 않는다. */
    public SchedulingServiceBuilder holds(NodePlacementHoldRepository v) { this.holds = v; return this; }

    /** 단건 배치 입력 저장을 붙인다. 붙이지 않으면 입력을 남기지 않는다. */
    public SchedulingServiceBuilder placementInputs(PlacementInputRepository v, ThermalModel thermal) {
        this.placementInputs = v;
        this.thermalModel = thermal;
        return this;
    }

    public SchedulingService build() {
        return new SchedulingService(
                workloads != null ? workloads : mock(WorkloadRepository.class),
                policies != null ? policies : mock(SchedulingPolicyRepository.class),
                collector != null ? collector : mock(NodeResourceCollector.class),
                placement != null ? placement : mock(PlacementService.class),
                engines != null ? engines : new EngineRegistry(List.of(new WeightedScoreScheduler())),
                evaluator != null ? evaluator : ConstraintEvaluator.withDefaults(),
                properties != null ? properties : new SchedulerProperties(),
                links, dispatch, budgets, placementInputs, thermalModel, holds);
    }
}
