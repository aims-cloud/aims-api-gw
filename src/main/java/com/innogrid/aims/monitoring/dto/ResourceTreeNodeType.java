package com.innogrid.aims.monitoring.dto;

/** 자원 탐색기 트리 항목 유형. */
public enum ResourceTreeNodeType {
    ROOT,
    ROLE,
    DEPLOYMENT,
    PHYSICAL_HOST_GROUP,
    PHYSICAL_HOST,
    VM_GROUP,
    VIRTUAL_MACHINE,
    CPU,
    MEMORY,
    NETWORK,
    FILE_SYSTEM,
    DISK,
    DISK_IO,
    PROCESS,
    PROCESS_STATUS,
    SWAP,
    SYSTEM,
    GPU_GROUP,
    GPU_DEVICE
}
