package com.innogrid.aims.scheduler.benchmark;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.LinkMatrix;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;

/**
 * 전략 비교의 <b>입력을 같게 만드는</b> 도구 — 셀마다 노드 사본·제약 평가기·제외 집합을 새로 짠다.
 *
 * <p>비교에서 숫자를 만드는 것은 전략이지만, 숫자를 <b>믿을 수 있게</b> 만드는 것은 입력의 동일성이다.
 * 이 클래스는 그 동일성을 깨는 세 가지를 한곳에서 막는다.</p>
 *
 * <ol>
 *   <li><b>노드는 가변이다.</b> {@link NodeResource#reserve} 로 상태가 바뀌므로 사본 없이 전략을
 *       잇달아 돌리면 앞 전략의 예약이 뒤 전략의 입력이 된다 — 비교가 아니라 누적 측정이 되고,
 *       조회용으로 나간 객체까지 오염된다. {@link #copyOf} 가 {@link NodeResource#snapshot} 사본만
 *       컨텍스트에 싣는다.</li>
 *   <li><b>기본 설정으로 조립한 평가기는 운영과 다른 임계값을 본다.</b>
 *       {@link ConstraintEvaluator#withDefaults()} 는 {@code new SchedulerProperties()} 를 쓰므로
 *       운영 환경변수({@code AIMS_SCHEDULER_*})로 덮어쓴 값이 통째로 사라진다. 응답만 봐서는 드러나지
 *       않는 어긋남이라 연구 근거로 쓰기 위험하다. {@link #freshEvaluator} 는 <b>주입받은 설정</b>으로만
 *       조립하고, 2인자 {@link PlacementContext#of(List, SchedulingPolicy)} 는 이 패키지에서 쓰지 않는다.</li>
 *   <li><b>읽기 검사가 상태를 남긴다.</b> 제약 평가기의 열 검사는 임계 온도를 넘은 노드를
 *       {@link ThermalModel#markCritical} 로 기록하고, 그 기록을 지우는 호출은 배치 경로에 없다.
 *       그래서 <b>후보를 전수 평가하는 전략만</b> 스스로 냉각 대기를 걸어 이후 판정이 달라진다 —
 *       첫 적격 노드에서 멈추는 first-fit 계열은 뜨거운 노드를 아예 건드리지 않아 이 대가를 치르지 않는다.
 *       셀마다 새 평가기를 만드는 것으로는 막히지 않는다(전략 하나가 한 셀 안에서 자기 발을 묶는다).
 *       그래서 {@link #freshEvaluator} 는 냉각 대기 자체를 끄고, {@link #exclusions} 로 제외 집합을
 *       <b>미리 한 번</b> 계산해 전 전략이 같은 것을 보게 한다.</li>
 * </ol>
 *
 * <p>스프링 빈이 아니다 — 하네스가 정적으로 부른다. 상태를 갖지 않으므로 병렬 실행에도 안전하다.</p>
 */
public final class BenchmarkContexts {

    private BenchmarkContexts() {
    }

    // ── 제약 평가기 ────────────────────────────────────────────

    /**
     * 셀마다 새로 만드는 제약 평가기. 반드시 <b>주입받은 설정</b>으로 조립한다.
     * 냉각 대기는 벤치마크에서 끈다({@link BenchmarkRules#COOLDOWN_MINUTES_FOR_BENCHMARK}).
     *
     * <p><b>전력 모델에는 주입받은 설정 객체를 그대로 넘긴다</b> — 사본을 뜨면 나중에 추가된 필드를
     * 조용히 놓쳐 다시 "운영과 다른 값" 문제로 돌아간다. 전력 모델은 설정을 읽기만 한다.</p>
     *
     * <p><b>온도 모델에만</b> 냉각 대기를 끈 설정을 넘긴다. 설정을 어떻게 끄는지는
     * {@code thermalPropsWithoutCooldown} 의 주석에 근거를 적었다.</p>
     *
     * @param props 운영에서 쓰는 설정. {@code null} 이면 예외 — 기본값으로 조립해 주는 편의는
     *              그 자체가 이 클래스가 막으려는 함정이다
     */
    public static ConstraintEvaluator freshEvaluator(SchedulerProperties props) {
        Objects.requireNonNull(props,
                "벤치마크 평가기는 운영 설정을 받아야 한다 — 기본값으로 조립하면 운영과 다른 임계값을 재게 된다");
        return new ConstraintEvaluator(
                new PowerModel(props),
                new ThermalModel(thermalPropsWithoutCooldown(props)));
    }

    /**
     * 온도 모델에만 줄 설정 — 열 설정을 <b>값으로 복사</b>하고 냉각 대기만 0 으로 둔다.
     *
     * <p><b>원본 빈을 변이하지 않는다.</b> {@code props.getThermal().setCooldownMinutes(0)} 은 한 줄로
     * 끝나지만 그 객체는 스프링이 관리하는 단일 인스턴스라, 벤치마크를 한 번 돌리는 것으로 운영 배치의
     * 히스테리시스가 영구히 꺼진다. 다른 요청과 스레드를 공유하므로 되돌려 놓는 방식도 안전하지 않다.</p>
     *
     * <p><b>{@code Clock} 고정으로는 끌 수 없다.</b> 온도 모델은 기록 시각과 경과 판정에 <b>같은 시계</b>를
     * 쓴다({@code markCritical} 이 {@code clock.instant()} 를 넣고 {@code isCoolingDown} 이 같은 시계로
     * 경과를 잰다). 시계를 한 시점에 고정하면 경과가 언제나 0분이 되어 {@code 0 >= 10} 이 거짓 —
     * 냉각 대기가 <b>풀리지 않고 영구히 걸린다.</b> 고치려는 것과 정반대다. 반대로 냉각 시간이 0 이면
     * 실제 시계로도 {@code 0 >= 0} 이 참이라 기록이 즉시 만료되고 지워진다. 그래서 시계가 아니라
     * 설정값을 0 으로 두는 것이 유일하게 맞는 수단이다.</p>
     *
     * <p>복사 대상은 온도 모델이 읽는 열 설정 셋뿐이다 — 온도 모델은 {@code getThermal()} 만 본다.
     * 열 설정에 필드가 늘면 이 메서드에도 한 줄을 더해야 한다. 점수·판정에 쓰이는 열관성 계수는
     * 테스트가 "주입된 값을 쓴다"로 지켜 누락을 잡는다.</p>
     */
    private static SchedulerProperties thermalPropsWithoutCooldown(SchedulerProperties source) {
        SchedulerProperties forThermalModel = new SchedulerProperties();

        SchedulerProperties.Thermal from = source.getThermal();
        SchedulerProperties.Thermal to = forThermalModel.getThermal();
        to.setDefaultWarningCelsius(from.getDefaultWarningCelsius());
        to.setInertiaK(from.getInertiaK());

        // 열 히스테리시스는 시계열 운영의 성질이라 단발 비교에 넣을 근거가 없다.
        to.setCooldownMinutes(BenchmarkRules.COOLDOWN_MINUTES_FOR_BENCHMARK);
        return forThermalModel;
    }

    // ── 배치 컨텍스트 사본 ─────────────────────────────────────

    /**
     * 노드는 snapshot 사본 + nodeId 오름차순. 평가기는 호출자가 지정한다(2인자 of 금지).
     *
     * <p><b>정렬하는 이유</b>: 첫 적격 노드에서 멈추는 전략의 결과는 후보 순서에 좌우된다. 수집기가
     * 돌려주는 순서는 병렬 질의가 끝난 순서라 실행마다 달라질 수 있어, 정렬하지 않으면 같은 판을
     * 같은 전략으로 두 번 돌려도 다른 숫자가 나온다. 정렬은 first-fit 계열에 임의의 불리·유리를 주지
     * 않기 위한 <b>결정론 장치</b>다.</p>
     *
     * <p>링크·전력 예산 원장은 여기서 잇지 않는다 — 원장을 실은 컨텍스트를 그대로 물려받으려면
     * {@link #copyOf(PlacementContext, ConstraintEvaluator)} 를 쓴다.</p>
     */
    public static PlacementContext copyOf(List<NodeResource> base, SchedulingPolicy policy,
                                          ConstraintEvaluator evaluator) {
        return build(base, policy, evaluator, LinkMatrix.EMPTY);
    }

    /**
     * <b>유일한 조립 지점.</b> 두 공개 {@code copyOf} 가 모두 여기를 지난다 — 노드 사본·정렬·평가기 검사가 한 곳에 있고,
     * 링크 원장은 인자로 받는다. 노드 목록에서 만드는 경로는 원장이 없어 {@link LinkMatrix#EMPTY} 를 넘기고, 컨텍스트에서
     * 잇는 경로는 그 컨텍스트의 원장을 넘긴다. 판 파일이 링크를 선언하게 되면 이 인자에 판의 행렬을 넣는 오버로드 하나로
     * 끝난다 — 두 경로 중 한쪽만 링크를 전달하던 갈림을 없애기 위한 자리다.
     */
    private static PlacementContext build(List<NodeResource> base, SchedulingPolicy policy,
                                          ConstraintEvaluator evaluator, LinkMatrix links) {
        Objects.requireNonNull(evaluator, "평가기를 호출자가 지정해야 한다 — 2인자 of 는 설정을 잃는다");

        List<NodeResource> copies = (base == null ? List.<NodeResource>of() : base).stream()
                .map(NodeResource::snapshot)
                .sorted(Comparator.comparing(NodeResource::getNodeId,
                        Comparator.nullsLast(Comparator.<String>naturalOrder())))
                .toList();

        return PlacementContext.of(copies, policy, evaluator).withLinkMatrix(links);
    }

    /**
     * 기준 컨텍스트를 그대로 잇는 사본 — 노드 사본·정책에 더해 <b>제외 목록과 원장 스냅샷</b>까지 옮긴다.
     *
     * <p>원장을 빠뜨리면 셀마다 제약이 달라진다. 링크 원장이 없으면 링크 단절 제약이 아무 노드도 막지
     * 않고, 전력 예산 원장이 없으면 MDC 합계 상한이 적용되지 않아 <b>벤치마크가 운영보다 느슨한 판</b>에서
     * 숫자를 낸다. 통합 진단의 샌드박스가 같은 이유로 두 원장을 명시적으로 잇는다.</p>
     */
    public static PlacementContext copyOf(PlacementContext source, ConstraintEvaluator evaluator) {
        Objects.requireNonNull(source, "기준 컨텍스트가 없다");

        return build(source.nodes(), source.policy(), evaluator, source.linkMatrix())
                .withExcluded(source.excludedNodes())
                .withMdcBudgets(source.mdcBudgets())
                .withPowerBudget(source.powerBudgetWatts());
    }

    // ── 제외 집합 사전 계산 ────────────────────────────────────

    /**
     * 워크로드 하나에 대한 제외 집합을 <b>미리 한 번</b> 계산한다(nodeId → 제외 사유, 적격은 없음).
     * 전 전략이 같은 것을 공유해야 first-fit 계열과 전수 평가 계열의 조건이 같아진다.
     *
     * <p>사전 계산이 필요한 이유는 제외 판정이 <b>순수하지 않다</b>는 데 있다. 열 검사는 임계 온도를
     * 넘은 노드를 기록하고, 그 기록은 이후 검사에서 "냉각 대기" 라는 다른 사유로 되돌아온다. 전략마다
     * 평가하는 노드 수가 다르니 부작용이 쌓이는 양도 달라져, 나중에 평가되는 노드의 판정이 <b>어느
     * 전략이 먼저 돌았는지</b>에 좌우된다. 한 번 계산해 나눠 주면 그 경로가 사라진다.</p>
     *
     * <p>프로브는 {@link #freshEvaluator} 로 만든 평가기를 든 컨텍스트여야 한다 — 냉각 대기가 켜진
     * 평가기로 계산하면 이 맵 자체가 "온도 초과" 대신 "냉각 대기" 를 담을 수 있고, 그 사유는 판이 아니라
     * 호출 횟수의 산물이다.</p>
     *
     * @return 부적격 노드만 담은 불변 맵. 프로브의 노드 순서(=nodeId 오름차순)를 유지한다
     */
    public static Map<String, String> exclusions(Workload workload, PlacementContext probe) {
        Objects.requireNonNull(workload, "워크로드가 없다");
        Objects.requireNonNull(probe, "프로브 컨텍스트가 없다");

        Map<String, String> reasonByNode = new LinkedHashMap<>();
        for (NodeResource node : probe.nodes()) {
            String reason = probe.exclusionReason(workload, node);
            if (reason != null) {
                reasonByNode.put(node.getNodeId(), reason);
            }
        }
        return Collections.unmodifiableMap(reasonByNode);
    }
}
