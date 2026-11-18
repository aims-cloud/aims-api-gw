package com.innogrid.aims.scheduler.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

import com.innogrid.aims.scheduler.entity.NodeExporterMapping;

/**
 * 익스포터 대응 화면이 고를 후보 — 저장소에 <b>지금</b> 있는 DCGM·IPMI 시리즈의 {@code instance}와, 수집기가 그것을
 * 어느 노드에 배정하는지.
 *
 * <p>배정({@link Assignment})은 수집기와 같은 규칙 {@link ExporterInstances}의 <b>역방향</b>이다. 규칙을 여기서
 * 다시 쓰지 않고 {@link ExporterInstances#dcgmFor}의 결과를 뒤집으므로, 화면이 보는 배정과 수집기가 실제로 하는 일이
 * 어긋날 수 없다.</p>
 *
 * <p><b>답하지 않은 질의는 그 질의가 뒷받침하는 필드를 비운다</b>(노드 목록 → 배정 {@link Assignment#UNKNOWN},
 * DCGM·IPMI 목록 → 그쪽 {@code ledgerOnly} 빈 목록). 못 받은 것을 "저장소에 없다"로 보이게 하지 않기 위해서다.</p>
 *
 * <p>메트릭 값은 쓰지 않는다. 발견 질의는 {@code count by(instance)}라 라벨과 시리즈 수만 있고, GPU 개수는 DCGM 쪽
 * 시리즈 수가 곧 카드 수라 함께 실린다.</p>
 *
 * @param dcgm           DCGM 시리즈 후보, instance 오름차순
 * @param ipmi           IPMI 시리즈 후보, instance 오름차순
 * @param nodeInstances  지금 수집 대상인 노드 익스포터 instance들({@code up == 1}), 오름차순
 * @param ledgerOnlyDcgm 원장에 적혀 있으나 저장소에 없는 DCGM instance — 오타이거나 익스포터가 내려간 것. DCGM 질의가
 *                       답하지 않았으면 대조할 수 없어 빈 목록
 * @param ledgerOnlyIpmi 같은 뜻의 IPMI
 * @param unavailable    상한 안에 답하지 않았거나 예외가 난 질의 이름({@code dcgm}·{@code ipmi}·{@code nodes}). 비어
 *                       있으면 세 질의가 모두 답했고, 그때 빈 후보 목록은 "그 익스포터 시리즈가 저장소에 없다"는 뜻이다
 */
public record ExporterInstanceCandidates(List<Candidate> dcgm, List<Candidate> ipmi, List<String> nodeInstances,
                                         List<String> ledgerOnlyDcgm, List<String> ledgerOnlyIpmi, List<String> unavailable) {

    public static final String QUERY_DCGM = "dcgm";
    public static final String QUERY_IPMI = "ipmi";
    public static final String QUERY_NODES = "nodes";

    /** 수집기가 이 instance를 어떻게 노드에 붙이는가. */
    public enum Assignment {
        /** 원장 행이 이 instance를 지목한다 */
        LEDGER,
        /** 포트를 뗀 호스트명이 같은 노드가 정확히 하나 — 원장 없이 이어진다 */
        HOSTNAME,
        /** 노드 익스포터 instance와 같은 문자열 — 같은 포트 배치·모의 모드 옛 형태 */
        SAME_AS_NODE,
        /** 둘 이상의 노드가 같은 시리즈를 얻어 수집기가 그 노드들을 자기 instance로 되돌린다 — 자기 시리즈가 있는 노드만 값이 남는다 */
        AMBIGUOUS,
        /** 어느 노드도 얻지 못한다 — 원장에 적어야 이어진다 */
        UNASSIGNED,
        /** 노드 목록 질의가 답하지 않아 배정을 계산할 수 없다 — 이 값일 때 배정을 읽으면 안 된다 */
        UNKNOWN
    }

    /**
     * @param instance         시리즈의 instance 라벨 그대로
     * @param host             포트를 뗀 호스트 — 화면이 노드와 나란히 놓고 보기 위한 것
     * @param gpuCount         DCGM이면 그 instance의 시리즈 수(= 카드 수), IPMI는 null
     * @param assignedNodeId   수집기가 이 instance를 붙이는 노드. {@code AMBIGUOUS}·{@code UNASSIGNED}·{@code UNKNOWN}이면 null
     * @param assignedBy       붙이는 근거
     * @param claimedByNodeIds 충돌 규칙 전에 이 instance로 해석되는 노드 전부(원장·호스트명·그대로), 오름차순. {@code AMBIGUOUS}면
     *                         둘 이상이고 그 노드들이 충돌 당사자다
     * @param ledgerNodeIds    원장에서 이 instance를 적은 노드 전부, 오름차순. 배정 근거가 원장이 아니어도 원장 행은 있을 수 있다
     */
    public record Candidate(String instance, String host, Integer gpuCount, String assignedNodeId, Assignment assignedBy,
                            List<String> claimedByNodeIds, List<String> ledgerNodeIds) {
        public Candidate {
            claimedByNodeIds = claimedByNodeIds == null ? List.of() : List.copyOf(claimedByNodeIds);
            ledgerNodeIds = ledgerNodeIds == null ? List.of() : List.copyOf(ledgerNodeIds);
        }
    }

    public ExporterInstanceCandidates {
        dcgm = dcgm == null ? List.of() : List.copyOf(dcgm);
        ipmi = ipmi == null ? List.of() : List.copyOf(ipmi);
        nodeInstances = nodeInstances == null ? List.of() : List.copyOf(nodeInstances);
        ledgerOnlyDcgm = ledgerOnlyDcgm == null ? List.of() : List.copyOf(ledgerOnlyDcgm);
        ledgerOnlyIpmi = ledgerOnlyIpmi == null ? List.of() : List.copyOf(ledgerOnlyIpmi);
        unavailable = unavailable == null ? List.of() : List.copyOf(unavailable);
    }

    /**
     * @param ledger        원장 행
     * @param dcgmCounts    저장소의 DCGM instance → 시리즈 수(질의가 답하지 않았으면 비어 있고 {@code unavailable}에 이름이 있다)
     * @param ipmiSeen      저장소의 IPMI instance들
     * @param nodeInstances 지금 수집 대상 노드 instance들
     * @param unavailable   답하지 않은 질의 이름
     */
    public static ExporterInstanceCandidates of(List<NodeExporterMapping> ledger, Map<String, Integer> dcgmCounts,
                                                Collection<String> ipmiSeen, Collection<String> nodeInstances,
                                                Collection<String> unavailable) {
        List<String> missing = unavailable == null ? List.of() : unavailable.stream().filter(Objects::nonNull).toList();
        Map<String, Integer> counts = new TreeMap<>();
        if (dcgmCounts != null) dcgmCounts.forEach((k, v) -> { if (k != null) counts.put(k, v); });
        Set<String> ipmi = new TreeSet<>();
        if (ipmiSeen != null) ipmiSeen.stream().filter(Objects::nonNull).forEach(ipmi::add);
        List<String> nodes = nodeInstances == null ? List.of() : nodeInstances.stream().filter(Objects::nonNull).sorted().distinct().toList();
        boolean nodesUnknown = missing.contains(QUERY_NODES);
        ExporterInstances resolved = ExporterInstances.of(ledger, counts.keySet(), ipmi);

        List<Candidate> dcgm = candidates(counts.keySet(), counts, nodes, nodesUnknown, resolved.dcgmByNode(),
                resolved.dcgmFor(nodes), resolved::dcgmOf);
        List<Candidate> ipmiOut = candidates(ipmi, null, nodes, nodesUnknown, resolved.ipmiByNode(), resolved.ipmiFor(nodes),
                resolved::ipmiOf);
        return new ExporterInstanceCandidates(dcgm, ipmiOut, nodes,
                missing.contains(QUERY_DCGM) ? List.of() : ledgerOnly(resolved.dcgmByNode(), counts.keySet()),
                missing.contains(QUERY_IPMI) ? List.of() : ledgerOnly(resolved.ipmiByNode(), ipmi),
                missing);
    }

    private static List<Candidate> candidates(Set<String> seen, Map<String, Integer> counts, List<String> nodes, boolean nodesUnknown,
                                              Map<String, String> ledgerByNode, Map<String, String> assignedByNode,
                                              UnaryOperator<String> singleResolve) {
        // instance → 원장에서 그것을 적은 노드들
        Map<String, List<String>> ledgerNodesOf = new LinkedHashMap<>();
        ledgerByNode.forEach((node, inst) -> ledgerNodesOf.computeIfAbsent(inst, k -> new ArrayList<>()).add(node));
        // instance → 충돌 규칙 전에 그 instance로 해석되는 노드들 (assign의 claims와 같은 셈, 한 번만 훑는다)
        Map<String, List<String>> claimants = new LinkedHashMap<>();
        for (String node : nodes) {
            String target = singleResolve.apply(node);
            if (target != null) claimants.computeIfAbsent(target, k -> new ArrayList<>()).add(node);
        }
        // instance → 수집기가 실제로 붙이는 노드 (충돌 규칙 뒤)
        Map<String, String> assignedNodeOf = new LinkedHashMap<>();
        assignedByNode.forEach((node, inst) -> assignedNodeOf.putIfAbsent(inst, node));

        List<Candidate> out = new ArrayList<>();
        for (String inst : seen) {
            List<String> ledgerNodes = ledgerNodesOf.getOrDefault(inst, List.of()).stream().sorted().toList();
            List<String> claimed = claimants.getOrDefault(inst, List.of()).stream().sorted().toList();
            String assigned = null;
            Assignment by;
            if (nodesUnknown) {
                by = Assignment.UNKNOWN;
                claimed = List.of();
            } else if ((assigned = assignedNodeOf.get(inst)) != null) {
                if (inst.equals(ledgerByNode.get(assigned))) by = Assignment.LEDGER;
                else if (inst.equals(assigned)) by = Assignment.SAME_AS_NODE;
                else by = Assignment.HOSTNAME;
            } else {
                by = claimed.size() >= 2 ? Assignment.AMBIGUOUS : Assignment.UNASSIGNED;
            }
            out.add(new Candidate(inst, ExporterInstances.hostOf(inst), counts == null ? null : counts.get(inst), assigned, by,
                    claimed, ledgerNodes));
        }
        out.sort(Comparator.comparing(Candidate::instance));
        return out;
    }

    private static List<String> ledgerOnly(Map<String, String> ledgerByNode, Set<String> seen) {
        return ledgerByNode.values().stream().filter(inst -> !seen.contains(inst)).sorted().distinct().toList();
    }
}
