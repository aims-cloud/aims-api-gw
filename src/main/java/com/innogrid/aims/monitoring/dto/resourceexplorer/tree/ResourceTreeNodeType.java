package com.innogrid.aims.monitoring.dto.resourceexplorer.tree;

import java.util.List;

/** 자원 탐색기 트리 항목 유형. */
public enum ResourceTreeNodeType {
    ROOT("전체 자원"),
    ROLE("역할"),
    DEPLOYMENT("배포영역"),
    PHYSICAL_HOST_GROUP("노드"),
    PHYSICAL_HOST("노드"),
    VM_GROUP("가상머신"),
    VIRTUAL_MACHINE("가상머신"),
    CPU("CPU"),
    MEMORY("Memory"),
    NETWORK("Network"),
    FILE_SYSTEM("File system"),
    DISK("Disk"),
    DISK_IO("Disk I/O"),
    PROCESS("Process"),
    PROCESS_STATUS("Process status"),
    SWAP("Swap"),
    SYSTEM("System"),
    GPU_GROUP("GPU"),
    GPU_DEVICE("GPU");

    /** 물리 노드 하위 모니터링 항목 (화면 순서) */
    public static final List<ResourceTreeNodeType> HOST_CATEGORIES =
            List.of(CPU, FILE_SYSTEM, MEMORY, NETWORK);

    /** 가상 머신 하위 모니터링 항목 (화면 순서) */
    public static final List<ResourceTreeNodeType> VM_CATEGORIES =
            List.of(CPU, DISK, DISK_IO, MEMORY, NETWORK,
                    PROCESS, PROCESS_STATUS, SWAP, SYSTEM);

    private final String displayName;

    ResourceTreeNodeType(String displayName) {
        this.displayName = displayName;
    }

    /** 트리에 노출되는 표시명. 화면설계서 표기를 따른다. */
    public String displayName() {
        return displayName;
    }
}
