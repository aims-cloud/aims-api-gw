package com.innogrid.aims.monitoring.dto.resourceexplorer.tree;

/**
 * 자원 탐색기 트리 항목 유형 (docs/06 자원 탐색기 트리 설계 §2).
 *
 * 모니터링 항목은 고정 목록으로 생성하지 않고 자원에 등록된 exporter 와 수집 지원 여부에 따라
 * 동적으로 구성한다 — exporter 별 항목 매핑은 PhysicalTreeBuilder 가 담당한다.
 */
public enum ResourceTreeNodeType {
    ROOT("전체 자원"),
    ROLE("역할"),

    // ── 물리 가지 (1세부) ───────────────────────────────────────
    PHYSICAL_ROOT("물리 노드"),
    MDC("MDC"),
    PHYSICAL_HOST("물리 호스트"),
    /** 물리 호스트에 배치된 1세부 VM 그룹 */
    VIRTUAL_RESOURCE_GROUP("가상자원"),
    /** 물리 호스트에서 실행 중인 1세부 VM */
    VIRTUAL_RESOURCE("가상자원"),

    // ── 가상머신 가지 (CB-Tumblebug) — 후속 단계에서 구성 ────────
    VM_ROOT("가상머신"),
    NAMESPACE("Namespace"),
    INFRA("Infra"),
    NODE_GROUP("NodeGroup"),
    UNCLASSIFIED("미분류"),
    VIRTUAL_MACHINE("가상머신"),

    // ── 모니터링 항목 (node_exporter) ───────────────────────────
    CPU("CPU"),
    MEMORY("Memory"),
    DISK("Disk"),
    DISK_IO("Disk I/O"),
    FILE_SYSTEM("File System"),
    NETWORK("Network"),
    PROCESS("Process"),
    SERVICE_STATUS("Service Status"),
    SWAP("Swap"),
    SYSTEM("System"),

    // ── 모니터링 항목 (dcgm_exporter) ───────────────────────────
    GPU_GROUP("GPU"),
    GPU_DEVICE("GPU"),

    // ── 모니터링 항목 (ipmi_exporter / snmp_exporter, 물리 호스트 전용) ──
    HARDWARE("Hardware"),
    HW_TEMPERATURE("Temperature"),
    HW_FAN("Fan"),
    HW_POWER("Power"),
    HW_VOLTAGE("Voltage / Current"),
    HW_HEALTH("Hardware Health"),
    HW_EVENTS("Hardware Events");

    private final String displayName;

    ResourceTreeNodeType(String displayName) {
        this.displayName = displayName;
    }

    /** 트리에 노출되는 표시명. 설계 문서 표기를 따른다. */
    public String displayName() {
        return displayName;
    }
}
