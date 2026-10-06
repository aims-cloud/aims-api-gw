package com.innogrid.aims.monitoring.entity;

/**
 * 수집 원장(collect_services·agent_nodes)의 소유자 — 물리 자산 1건 또는 Tumblebug VM 1건 (docs/11 §4).
 *
 * 물리 노드는 자산 id, VM 은 Tumblebug 의 nsId·infraId·nodeId 로 식별한다. 한 행은 둘 중 하나만 갖는다(DB CHECK).
 * VM 은 자산 원장에 없으므로 Tumblebug 키가 곧 식별자이며, 메트릭·로그 라벨(tb_ns/tb_infra/tb_node)과 글자까지 같다.
 */
public record CollectOwner(Long assetId, String tbNs, String tbInfra, String tbNode) {

    public CollectOwner {
        boolean asset = assetId != null;
        boolean vm = tbNs != null || tbInfra != null || tbNode != null;
        if (asset == vm || (vm && (isBlank(tbNs) || isBlank(tbInfra) || isBlank(tbNode)))) {
            throw new IllegalArgumentException("수집 원장 소유자는 자산 id 또는 Tumblebug 키(ns·infra·node) 중 하나여야 합니다.");
        }
    }

    public static CollectOwner asset(long assetId) {
        return new CollectOwner(assetId, null, null, null);
    }

    public static CollectOwner vm(String tbNs, String tbInfra, String tbNode) {
        return new CollectOwner(null, tbNs, tbInfra, tbNode);
    }

    public boolean isVm() {
        return assetId == null;
    }

    /** 로그용 짧은 표기 — asset:12 또는 tb:ns/infra/node */
    public String label() {
        return isVm() ? "tb:" + tbNs + "/" + tbInfra + "/" + tbNode : "asset:" + assetId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
