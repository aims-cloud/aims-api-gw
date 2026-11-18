package com.innogrid.aims.scheduler.model;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.innogrid.aims.scheduler.entity.NodeExporterMapping;

/**
 * 노드 한 대의 세 익스포터 {@code instance}를 잇는 해석기 — 수집 1회당 한 번 만들어 노드마다 DB를 치지 않는다.
 *
 * <p>프로메테우스의 {@code instance} 라벨은 스크레이프 주소 그대로라 익스포터마다 다르다. 노드 익스포터는
 * {@code host:9100}, DCGM은 {@code host:9400}, IPMI는 {@code ipmi-exporter:9290} 같은 중앙 주소다(2026-09-21
 * 배포 서버 실측). 노드 익스포터 값을 키로 질의하면 실장비에서 GPU 개수는 항상 0, 전력·온도는 항상 미계측이 된다.</p>
 *
 * <p><b>세 단계로 해석한다.</b>
 * <ol>
 *   <li><b>원장</b> — 운영자가 {@link NodeExporterMapping}에 적어 둔 값. 중앙 IPMI처럼 호스트명으로 이을 수 없는
 *       형태는 이 길밖에 없다.</li>
 *   <li><b>호스트명 일치</b> — 포트를 뗀 호스트가 같은 그 익스포터 instance가 저장소에 <b>정확히 하나</b>면
 *       그것. DCGM이 GPU 노드에서 돌아 포트만 다른 배치를 원장 없이 덮는다. 둘 이상이면 고르지 않는다. 틀린
 *       노드의 값을 끌어오는 것보다 미계측이 낫다.</li>
 *   <li><b>그대로</b> — 노드 익스포터 instance를 그대로 쓴다.</li>
 * </ol></p>
 */
public record ExporterInstances(Map<String, String> dcgmByNode, Map<String, String> ipmiByNode,
                                Set<String> dcgmSeen, Set<String> ipmiSeen) {

    public static final ExporterInstances EMPTY = new ExporterInstances(Map.of(), Map.of(), Set.of(), Set.of());

    public ExporterInstances {
        dcgmByNode = dcgmByNode == null ? Map.of() : Map.copyOf(dcgmByNode);
        ipmiByNode = ipmiByNode == null ? Map.of() : Map.copyOf(ipmiByNode);
        dcgmSeen = dcgmSeen == null ? Set.of() : Set.copyOf(dcgmSeen);
        ipmiSeen = ipmiSeen == null ? Set.of() : Set.copyOf(ipmiSeen);
    }

    /**
     * @param ledger   원장 행. 같은 노드가 둘이면(유니크 제약이 막지만) 뒤엣것
     * @param dcgmSeen 저장소에 지금 있는 DCGM 시리즈의 instance들
     * @param ipmiSeen 저장소에 지금 있는 IPMI 시리즈의 instance들
     */
    public static ExporterInstances of(List<NodeExporterMapping> ledger, Collection<String> dcgmSeen, Collection<String> ipmiSeen) {
        Map<String, String> dcgm = new LinkedHashMap<>();
        Map<String, String> ipmi = new LinkedHashMap<>();
        if (ledger != null) {
            for (NodeExporterMapping m : ledger) {
                if (m.getNodeId() == null) continue;
                // 키도 다듬는다 — API는 저장 전에 trim 하지만 스크립트·직접 INSERT로 들어온 행은 그렇지 않을 수 있다.
                String node = m.getNodeId().trim();
                if (m.getDcgmInstance() != null && !m.getDcgmInstance().isBlank()) dcgm.put(node, m.getDcgmInstance().trim());
                if (m.getIpmiInstance() != null && !m.getIpmiInstance().isBlank()) ipmi.put(node, m.getIpmiInstance().trim());
            }
        }
        return new ExporterInstances(dcgm, ipmi,
                dcgmSeen == null ? Set.of() : Set.copyOf(dcgmSeen),
                ipmiSeen == null ? Set.of() : Set.copyOf(ipmiSeen));
    }

    /** 이 노드의 DCGM 시리즈 instance. 원장 → 호스트명 일치 → 그대로. */
    public String dcgmOf(String nodeInstance) {
        return resolve(nodeInstance, dcgmByNode, dcgmSeen);
    }

    /** 이 노드의 IPMI 시리즈 instance. 원장 → 호스트명 일치 → 그대로. */
    public String ipmiOf(String nodeInstance) {
        return resolve(nodeInstance, ipmiByNode, ipmiSeen);
    }

    /**
     * 여러 노드를 한 번에 — 노드 → DCGM instance. <b>같은 시리즈로 해석된 노드가 둘 이상이면 그 노드들은 전부 그대로(자기
     * instance)로 떨어진다.</b> 한 시리즈를 두 노드에 넣으면 값이 두 번 세어져 클러스터 합계·에너지가 부푼다 — 후보가
     * 둘이면 고르지 않는다는 원칙의 같은 결이다. 중앙 IPMI 익스포터를 여러 노드에 적어 둔 원장이 바로 이 경우다.
     */
    public Map<String, String> dcgmFor(Collection<String> nodeInstances) {
        return assign(nodeInstances, dcgmByNode, dcgmSeen);
    }

    /** 여러 노드를 한 번에 — 노드 → IPMI instance. 충돌 규칙은 {@link #dcgmFor}와 같다. */
    public Map<String, String> ipmiFor(Collection<String> nodeInstances) {
        return assign(nodeInstances, ipmiByNode, ipmiSeen);
    }

    /**
     * 이 노드의 그 익스포터 시리즈가 저장소에 실제로 있는가 — 원장·호스트명으로 이어졌거나, 노드 instance 그대로의
     * 시리즈가 있는 경우. 아무것도 못 찾아 그대로 떨어진 경우만 false. 테스트용.
     */
    public boolean resolvedDcgm(String nodeInstance) { return !dcgmOf(nodeInstance).equals(nodeInstance) || dcgmSeen.contains(nodeInstance); }
    public boolean resolvedIpmi(String nodeInstance) { return !ipmiOf(nodeInstance).equals(nodeInstance) || ipmiSeen.contains(nodeInstance); }

    private static Map<String, String> assign(Collection<String> nodeInstances, Map<String, String> ledger, Set<String> seen) {
        Map<String, String> out = new LinkedHashMap<>();
        if (nodeInstances == null) return out;
        Map<String, Integer> claims = new HashMap<>();
        for (String node : nodeInstances) {
            if (node == null) continue;
            String target = resolve(node, ledger, seen);
            out.put(node, target);
            claims.merge(target, 1, Integer::sum);
        }
        // 둘 이상이 같은 시리즈를 가리키면 전부 자기 instance로 — 자기 시리즈가 있는 노드만 값이 남고 나머지는 미계측
        out.replaceAll((node, target) -> claims.get(target) > 1 ? node : target);
        return out;
    }

    private static String resolve(String nodeInstance, Map<String, String> ledger, Set<String> seen) {
        if (nodeInstance == null) return null;
        String fromLedger = ledger.get(nodeInstance);
        if (fromLedger != null) return fromLedger;
        // 저장소가 그 익스포터의 시리즈를 노드 instance 그대로 내고 있으면 이을 것이 없다(모의 모드 옛 형태·같은 포트 배치).
        if (seen.contains(nodeInstance)) return nodeInstance;
        String host = hostOf(nodeInstance);
        String match = null;
        for (String candidate : seen) {
            // 호스트명은 대소문자를 가리지 않는다 — 스크레이프 설정에 적힌 철자가 익스포터마다 다를 수 있다
            if (hostOf(candidate).equalsIgnoreCase(host)) {
                if (match != null) return nodeInstance;   // 둘 이상 — 고르지 않는다
                match = candidate;
            }
        }
        return match != null ? match : nodeInstance;
    }

    /**
     * {@code host:port}에서 호스트. 포트가 없으면 그대로. IPv6 대괄호 표기({@code [::1]:9100})는 대괄호 안을 호스트로 본다.
     */
    static String hostOf(String instance) {
        if (instance == null) return "";
        String s = instance.trim();
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            return close > 0 ? s.substring(1, close) : s;
        }
        int colon = s.lastIndexOf(':');
        if (colon < 0) return s;
        // 콜론이 둘 이상이면 포트 없는 IPv6로 본다
        if (s.indexOf(':') != colon) return s;
        return s.substring(0, colon);
    }

}
