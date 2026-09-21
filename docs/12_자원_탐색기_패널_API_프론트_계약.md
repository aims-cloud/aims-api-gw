# 12. 자원 탐색기 패널 API 프론트 계약 — 관측 > 자원 탐색기 (트리 식별자 · 그룹 루트 · 노드/VM 항목 패널)

- 작성일: 2026-09-17
- 대상: 프론트 `src/features/resource-explorer/{api/resourceExplorerApi.ts, model/explorerSelection.ts, types/explorer.ts}` (specs/008-resource-explorer)
- 기준 코드: 백엔드 `feat/monitoring` — `ResourceExplorerTreeController`, `ResourceExplorerPhysicalRootController`, `ResourceExplorerVmRootController`, `controller/resourceexplorer/node/*`, `controller/resourceexplorer/vm/*`
- 관련 문서: docs/06 자원 탐색기 트리 설계 v0.6(§4.3 식별자 필드, §5.6 항목별 패널 API, §9.4 수집 상태), docs/03 수집 SW 설치(node_exporter 플래그)

프론트 스펙(api-ui-differences.md · ui-design-decisions.md)이 "백엔드 미제공"으로 적어 둔 항목 가운데 2026-09-16~17 에 채워진 것을 먼저 적고(§1), 그 뒤에 공통 규칙(§2)과 엔드포인트별 계약(§3~§6), 배포 전제(§7), 확인이 필요한 사항(§8)을 정리한다. 역할·MDC·Namespace·Infra·NodeGroup·VM 상세·GPU 패널은 기존 Swagger 와 같으므로 여기서 다시 적지 않는다.

## 1. 프론트 스펙 대비 바뀐 것 (반영 필요)

| # | 프론트 현재 | 백엔드 현재 | 조치 |
|---|---|---|---|
| 1 | DIFF-031 / B-1 — 트리 응답에 `nsId·infraId·nodeGroupId` 없음 → "식별자 미제공" 안내, 요청 0 | 트리 Namespace~VM 노드에 **`nsId`·`infraId`·`nodeGroupId`·`vmId`** 필드 제공(§3). 물리 가지에는 없음(응답에서 생략) | `ExplorerTreeItem` 에 `vmId: string \| null` 추가. `explorerSelection` 의 `unsupported('missing-identifier')` 분기는 그대로 두되 실서버에서는 더 이상 걸리지 않는다 |
| 2 | VM 계열 API 의 VM 식별 파라미터 `nodeId` 에 `vmNode.resourceId`(`TB:…`) 전달 | `/vm/*` 전부 **`vmId`** 파라미터(Tumblebug node id). `nodeId` 는 물리 노드 패널(`/node/{nodeId}`, assets.id)에만 쓴다 | `resourceExplorerApi.ts` `vmParams`: `nodeId: scope.vmId` → `vmId: scope.vmId`. `VmScope.vmId` 값은 `resourceId` 가 아니라 트리 VIRTUAL_MACHINE 노드의 **`vmId` 필드** |
| 3 | DESIGN-012 — PHYSICAL_ROOT/VM_ROOT 전용 API 없음 → `GroupRootNotice` 가 `role/{roleName}/areas` 재표시 | `role/{roleName}/physical-root/*`·`role/{roleName}/vm-root/*` 6종 신설(§4) | `GroupRootNotice` 를 KPI 카드 + 상태 도넛 + MDC/Namespace 목록 화면으로 교체. 목록 행의 `mdcId`·`nsId` 로 트리 노드 매칭 |
| 4 | DESIGN-014 — 노드 Disk I/O·Swap·System·Process·Service Status `unsupported('no-api')` | `/node/{nodeId}/{disk-io\|swap\|system\|process\|service-status}` 신설(§5). File System 은 기존 6종 유지 | `NodeCategory` 에 `diskIo\|swap\|system\|process\|serviceStatus` 추가, `no-api` 는 HW_* 만 남김 |
| 5 | VM 카테고리 `VmCategoryKind = cpu\|memory\|network` 만 | `/vm/{file-system\|disk-io\|swap\|system\|process\|service-status}` 신설(§6). 응답 DTO 는 노드 패널과 **동일** | 노드 패널 컴포넌트를 VM 에 재사용. 파라미터만 `nodeId` path → `nsId·infraId·vmId` query |
| 6 | 노드 Memory·Network 개요 탭 "요약 API 없음"(DESIGN-008) | 변경 없음 — Memory·Network 는 `metrics/trend` 만 제공(노드·VM 동일). 개요 KPI 는 노드 상세 `summary` 의 memory·networkTraffic 카드를 쓴다 | 기존 판단 유지 |

## 2. 공통

- 기본 경로 `/api/v1/monitoring/resource-explorer`, 인증 Bearer JWT. 응답 래퍼 `ApiResponse<T> { success, data, message }`.
- 목록은 `PageResponse<T> { items, page, size, totalElements, totalPages }`, **page 는 1부터**, size 1~100, `sort={필드},{asc|desc}`(허용 필드 밖이면 400).
- 자원 식별
  - 물리 노드: path `/node/{nodeId}` — `nodeId` = 트리 PHYSICAL_HOST·하위 항목의 `resourceId`(assets.id).
  - VM: query `nsId`·`infraId`·`vmId`(필수) + `roleName`(선택, 주면 그 역할 스코프로만 판정) — 트리 VIRTUAL_MACHINE 노드의 같은 이름 필드(§3). 하위 항목(CPU 등)을 선택했을 때는 조상 VIRTUAL_MACHINE 노드에서 읽는다.
  - 스코프 밖·존재하지 않는 노드/VM 은 **404**(존재를 드러내지 않음).
- 시계열 공통: `interval` ∈ `LAST_10M|LAST_1H|LAST_6H|LAST_24H|LAST_30D|LAST_90D`(기본 `LAST_10M`), 조회 구간은 **지금 기준** interval 기간, 버킷 간격 `step` 은 기간별 고정 `30s|5m|15m|1h|1d|3d`. from/to 파라미터 없음.
  - 응답 `{ metric, unit, from(epoch 초), to(epoch 초), step, series[{ name, dataPoints[{ timestamp(epoch 초), value }] }] }`. Disk I/O 는 `device` 필드가 추가된다.
  - 미수집이면 `series: []`. 값이 0 인 것과 미수집(빈 시리즈)을 구분해 그린다.
- 미수집 표현: KPI 는 `null`, 목록은 빈 페이지, 시계열은 빈 시리즈. 값 `0` 은 실제 0 이다.
- 수집 상태 문자열(`collectStatus`·트리 `monitoringStatus`): `COLLECTING` | `NO_DATA` | `NOT_CONFIGURED` | `UNAVAILABLE` (docs/06 §9.4). VM 은 exporter 원장이 없어 `NOT_CONFIGURED` 대신 표본 유무로만 판정한다.
- rate 계열 KPI(CPU %, I/O, 네트워크, fork, 컨텍스트 스위치 등)는 **최근 5분** rate 이다.

## 3. 트리 식별자 필드 (`GET /tree/roots`, `/tree/{treeNodeId}/children`, `/tree/search`)

`ResourceTreeNodeResponse` 에 아래 4 필드가 추가됐다. null 이면 응답에서 생략된다(`@JsonInclude(NON_NULL)`).

| type | `nsId` | `infraId` | `nodeGroupId` | `vmId` |
|---|---|---|---|---|
| NAMESPACE | ○ | – | – | – |
| INFRA | ○ | ○ | – | – |
| NODE_GROUP | ○ | ○ | 그룹 id | – |
| UNCLASSIFIED | ○ | ○ | `@unclassified` | – |
| VIRTUAL_MACHINE | ○ | ○ | 그룹 id 또는 `@unclassified` | Tumblebug node id |
| VM 하위 항목(CPU·GPU 그룹 …) | – | – | – | – (조상 VM 노드에서 읽음) |
| 물리 가지 전체 | – | – | – | – |

- `nodeGroupId` 값은 `/node-group/*` API 의 `nodeGroupId` 파라미터에 그대로 쓴다(미분류 `@unclassified` 포함).
- `vmId` 값은 `/vm/*` API 의 `vmId` 파라미터에 그대로 쓴다. `resourceId`(`TB:{ns}/{infra}/{node}`)는 알람 스코프·표시용이며 파싱하지 않는다.
- 기존 필드(`id`·`parentId`·`name`·`type`·`resourceId`·`roleName`·`mdcId`·`provider`·`status`·`monitoringStatus`·`leaf`·`childCount`·`children`)는 그대로다.

## 4. 그룹 루트 패널 — `GET /role/{roleName}/physical-root/*`, `/role/{roleName}/vm-root/*`

`roleName` 은 트리 ROLE 노드의 `roleName`. 토큰에 없는 역할이면 404.

### 4.1 물리 노드 그룹(PHYSICAL_ROOT)

| 경로 | 응답 |
|---|---|
| `/physical-root/summary` | `PhysicalRootSummaryResponse { mdcCount, nodeCount, vmCount, runningCount, pendingCount, stopCount, errorCount }` — 상태 카운트는 **물리 호스트** 기준, `vmCount` 는 호스트 위 1세부 가상자원 |
| `/physical-root/node-status` | `ResourceStatusDistributionResponse { resourceKind:"NODE", totalCount, statusCounts[{status, count}] }` — Running/Error/Pending/Stop 순, 0 도 포함. 역할 패널 `node-status` 와 같은 값 |
| `/physical-root/mdcs?keyword&page&size&sort=name,asc` | `Page<PhysicalRootMdcListItemResponse { mdcId, name, nodeCount, nodeStatus{total,running,error,pending,stop}, vmCount, vmStatus{…} }>` — sort `name\|nodeCount\|vmCount`. 행의 `mdcId` = 트리 MDC 노드 `mdcId` |

### 4.2 가상머신 그룹(VM_ROOT)

| 경로 | 응답 |
|---|---|
| `/vm-root/summary` | `VmRootSummaryResponse { namespaceCount, infraCount, vmCount, runningCount, pendingCount, stopCount, errorCount, tumblebugUnavailable }` — 역할에 매핑됐고 Tumblebug 에 존재하는 Namespace 만 집계(트리와 같은 기준). `tumblebugUnavailable=true` 면 이번 갱신 조회가 하나라도 실패해 이전 값이 섞여 있음 |
| `/vm-root/vm-status` | `ResourceStatusDistributionResponse { resourceKind:"VM", … }` |
| `/vm-root/namespaces?keyword&page&size&sort=name,asc` | `Page<VmRootNamespaceListItemResponse { nsId, name, infraCount, vmCount, vmStatus{…}, tumblebugUnavailable }>` — keyword 는 이름·nsId, sort `name\|infraCount\|vmCount`. 행의 `nsId` = 트리 NAMESPACE 노드 `nsId` |

VM 상태 버킷은 Tumblebug 상태 원문의 §6.1 규칙(Running→RUNNING, Creating·Rebooting 등→PENDING, Suspended·Terminated→STOP, Failed·Undefined→ERROR)이다.

## 5. 노드 항목 패널 — `GET /node/{nodeId}/{category}/…`

경로 규칙: `summary`(개요 KPI) · 목록(`devices`·`mounts`·`units`) · `info`(정보 표) · `metrics/trend?metric&interval`(모니터링 차트). exporter 가 등록되지 않은 노드(트리 항목 `NOT_CONFIGURED`)는 Thanos 를 조회하지 않고 미수집으로 응답한다.

### 5.1 CPU `/cpu`

| 경로 | 파라미터 | 응답 |
|---|---|---|
| `/summary` | – | `CpuSummaryResponse { usedPercent, ioWaitPercent, systemPercent, stealPercent }` |
| `/devices` | `page size sort=cpuId,asc`(`cpuId\|socket\|vendor\|model\|coreCount\|threadCount\|baseClockGhz`) | `Page<CpuDeviceListItemResponse { cpuId("cpu-{socket}"), socket, vendor, model, coreCount, threadCount, baseClockGhz, collectStatus, lastCollectedAt }>` |
| `/metrics/trend` | `metric=CPU_USED`(`CPU_USED\|CPU_IDLE\|CPU_USER\|CPU_SYSTEM\|CPU_IO_WAIT\|CPU_NICE\|CPU_IRQ\|CPU_SOFT_IRQ\|CPU_STEAL`) `interval` | 시리즈 `cpu`, 단위 `%` |

### 5.2 Memory `/memory`

| 경로 | 파라미터 | 응답 |
|---|---|---|
| `/metrics/trend` | `metric=MEMORY_USED`(`MEMORY_USED\|MEMORY_USED_PERCENT\|MEMORY_FREE\|MEMORY_FREE_PERCENT\|MEMORY_TOTAL`) `interval` | 시리즈 `memory`, 단위 `GB` 또는 `%` |

### 5.3 Network `/network`

| 경로 | 파라미터 | 응답 |
|---|---|---|
| `/metrics/trend` | `metric=NETWORK_TOTAL`(`NETWORK_TOTAL\|NETWORK_RECV\|NETWORK_SENT\|NETWORK_PACKETS_RECV\|NETWORK_PACKETS_SENT\|NETWORK_ERROR_IN\|NETWORK_ERROR_OUT\|NETWORK_DROP_IN\|NETWORK_DROP_OUT`) `interval` | 시리즈 `network`, 단위 `B/s`·`pps`·`/s`. 단위 환산(KB/MB)은 프론트 |

### 5.4 File System `/file-system`

그룹(트리 File System 항목)과 개별 마운트(하위 leaf, `resourceId` = mountPath)를 한 컨트롤러가 제공한다. 마운트 경로에 `/` 가 있어 개별 마운트는 `mountPath` 쿼리로 식별한다. tmpfs·overlay 제외. Near Full 기준 90%.

| 경로 | 파라미터 | 응답 |
|---|---|---|
| `/summary` | – | `FileSystemGroupSummaryResponse { totalMountCount, nearFullCount, maxUsedPercent, maxUsedPercentMountPath, maxUsedGb, maxUsedGbMountPath, totalSizeGb, totalUsedGb, totalFreeGb, maxInodeUsagePercent(null 가능) }` |
| `/mounts` | `page size sort=mountPath,asc`(`mountPath\|fsType\|totalGb\|usedPercent\|usedGb\|freeGb\|inodeUsagePercent`) | `Page<FileSystemMountListItemResponse { mountPath, fsType, totalGb, usedPercent, usedGb, freeGb, inodeUsagePercent }>` |
| `/metrics/trend` | `metric=FS_USED`(`FS_USED\|FS_USED_PERCENT\|FS_FREE\|FS_FREE_PERCENT\|FS_TOTAL\|FS_INODE_USAGE_PERCENT`) `interval` | 마운트 전체 합산, 시리즈 `file_system` |
| `/mount/summary` | `mountPath` | `FileSystemMountSummaryResponse { totalGb, usedPercent, usedGb, freeGb, growth24hGb(null 가능, 감소 시 음수), inodeUsagePercent }` — 표본 없는 마운트 404 |
| `/mount/info` | `mountPath` | `FileSystemMountInfoResponse { mountPath, fsType, collectStatus, lastCollectedAt }` — 404 동일 |
| `/mount/metrics/trend` | `mountPath metric interval` | 해당 마운트 시계열 |

### 5.5 Disk I/O `/disk-io`

loop·ram·dm-·sr 장치 제외.

| 경로 | 파라미터 | 응답 |
|---|---|---|
| `/summary` | – | `DiskIoSummaryResponse { deviceCount, readBytesPerSec, writeBytesPerSec, readIops, writeIops, readLatencyMs, writeLatencyMs, utilizationPercent }` — 바이트·IOPS 합산, 지연은 IOPS 가중 평균, 사용률은 장치 평균. 장치 없으면 `deviceCount 0` 에 나머지 null |
| `/devices` | `page size sort=device,asc`(`device\|readBytesPerSec\|writeBytesPerSec\|readIops\|writeIops\|readLatencyMs\|writeLatencyMs\|utilizationPercent`) | `Page<DiskIoDeviceListItemResponse { device, readBytesPerSec, writeBytesPerSec, readIops, writeIops, readLatencyMs(null=구간에 I/O 없음), writeLatencyMs, utilizationPercent }>` |
| `/metrics/trend` | `metric=DISK_READ_BYTES`(`DISK_READ_BYTES\|DISK_WRITE_BYTES\|DISK_READ_IOPS\|DISK_WRITE_IOPS\|DISK_READ_LATENCY\|DISK_WRITE_LATENCY\|DISK_UTILIZATION`) `device?` `interval` | `device` 없으면 전체 합산(시리즈 `disk_io`, 응답 `device:null`), 있으면 그 장치(시리즈 = 장치명) |

### 5.6 Swap `/swap`

| 경로 | 파라미터 | 응답 |
|---|---|---|
| `/summary` | – | `SwapSummaryResponse { totalGb, usedGb, freeGb, usedPercent, swapInPerSec, swapOutPerSec }` — 스왑 없는 호스트는 용량 0 에 `usedPercent null` |
| `/metrics/trend` | `metric=SWAP_USED`(`SWAP_USED\|SWAP_USED_PERCENT\|SWAP_FREE\|SWAP_TOTAL\|SWAP_IN\|SWAP_OUT`) `interval` | 시리즈 `swap`, 단위 `GB`·`%`·`pages/s` |

### 5.7 System `/system`

| 경로 | 파라미터 | 응답 |
|---|---|---|
| `/summary` | – | `SystemSummaryResponse { uptimeSeconds, load1, load5, load15, coreCount, contextSwitchesPerSec, interruptsPerSec }` — 가동 시간은 초, 일/시간 표기는 프론트 |
| `/info` | – | `SystemInfoResponse { hostname, osName, osVersion, kernelRelease, architecture, bootTime, collectStatus, lastCollectedAt }` — exporter 없음 `NOT_CONFIGURED`, 표본 없음 `UNAVAILABLE`(값 전부 null) |
| `/metrics/trend` | `metric=LOAD_1`(`LOAD_1\|LOAD_5\|LOAD_15\|CONTEXT_SWITCHES\|INTERRUPTS`) `interval` | 시리즈 `system`, 부하는 단위 `""`, 나머지 `/s` |

### 5.8 Process `/process`

PID 별 프로세스 목록은 제공하지 않는다(docs/06 §5.4).

| 경로 | 파라미터 | 응답 |
|---|---|---|
| `/summary` | – | `ProcessSummaryResponse { runningCount, blockedCount, processCount, threadCount, forksPerSec, processesByState[{state, count}] }` — `processCount`·`threadCount`·`processesByState` 는 `--collector.processes` 가 켜진 노드에서만 값이 있고 없으면 null/빈 목록 |
| `/metrics/trend` | `metric=PROCS_RUNNING`(`PROCS_RUNNING\|PROCS_BLOCKED\|PROCESSES_TOTAL\|THREADS\|FORKS_RATE`) `interval` | 시리즈 `process`, 단위 `count`·`/s` |

### 5.9 Service Status `/service-status`

`--collector.systemd` 가 켜진 노드에서만 데이터가 있다(§7).

| 경로 | 파라미터 | 응답 |
|---|---|---|
| `/summary` | – | `ServiceStatusSummaryResponse { totalCount, activeCount, inactiveCount, failedCount, activatingCount, deactivatingCount }` — 표본 없으면 전부 null |
| `/units` | `state?`(`ACTIVE\|INACTIVE\|FAILED\|ACTIVATING\|DEACTIVATING`) `keyword?`(유닛명 부분 일치, 대소문자 무시) `page size sort=name,asc`(`name\|type\|state`) | `Page<ServiceUnitListItemResponse { name, type, state }>` — `state` 는 위 enum 문자열 |
| `/metrics/trend` | `metric=ACTIVE_UNITS`(`ACTIVE_UNITS\|FAILED_UNITS\|INACTIVE_UNITS`) `interval` | 시리즈 `service_status`, 단위 `count`, 정수 |

## 6. VM 항목 패널 — `GET /vm/{category}/…?roleName?&nsId&infraId&vmId`

경로·응답은 §5 와 같고 자원 식별만 query 로 바뀐다(`/vm/file-system/mount/summary?nsId&infraId&vmId&mountPath` 등). 노드와 다른 점만 적는다.

| 카테고리 | 노드와 다른 점 |
|---|---|
| CPU `/vm/cpu` | `metric` 값 이름은 같으나 enum 이 `VmCpuMetric`. `/devices` 응답은 `VmCpuListItemResponse { socket, vendor, model, coreCount, threadCount, collectStatus }`(cpuId·baseClockGhz·lastCollectedAt 없음), sort `socket\|vendor\|model\|coreCount\|threadCount` |
| Memory `/vm/memory` | `metric` 9종 `MEMORY_USED\|MEMORY_USED_PERCENT\|MEMORY_FREE\|MEMORY_AVAILABLE\|MEMORY_AVAILABLE_PERCENT\|MEMORY_TOTAL\|MEMORY_CACHED\|MEMORY_BUFFERED\|MEMORY_SHARED` |
| Network `/vm/network` | `metric` 8종 — `NETWORK_TOTAL` 없음(기본 `NETWORK_RECV`) |
| File System·Disk I/O·Swap·System·Process·Service Status | 응답 DTO **동일**. System `/info` 의 `collectStatus` 는 `COLLECTING` 또는 `UNAVAILABLE` 만(VM 은 `NOT_CONFIGURED` 없음). System 정보는 게스트 OS 안에서 본 호스트명·커널이며 Tumblebug 사양·이미지는 `vm/info` |
| GPU `/vm/gpu` | 기존 인스턴스 모델 유지(변경 없음) |

VM 은 exporter 등록 원장이 없어 항상 Thanos 를 조회하며, 표본이 없으면 미수집으로 응답한다. 트리의 VM 하위 항목 `monitoringStatus` 는 최근 24시간 `tb_ns/tb_infra/tb_node` 라벨 표본 유무로 판정한다(docs/06 §9.4).

## 7. 배포 전제 (수집 상태에 영향)

| 대상 | 필요한 node_exporter 플래그 | 없을 때 |
|---|---|---|
| Process 전체·스레드·상태별 | `--collector.processes` | `processCount`·`threadCount` null, `processesByState` 빈 목록, `PROCESSES_TOTAL`·`THREADS` 빈 시리즈 |
| Service Status 전체 | `--collector.systemd` | 트리 항목 `NO_DATA`, summary 전부 null, units 빈 페이지, trend 빈 시리즈 |

- Tumblebug VM 배포 스크립트(docs/07)에는 두 플래그가 포함되어 있다.
- k8s-cp 물리 노드는 2026-09-17 기준 두 플래그가 없다 → docs/03 ExecStart 기준으로 추가 후 `systemctl restart node_exporter`. 반영 전까지 k8s-cp 의 Service Status 는 `NO_DATA`, Process 는 부분 null 이 정상 동작이다.

## 8. 확인·결정이 필요한 사항 (프론트 ↔ 백엔드)

| # | 항목 | 백엔드 의견 |
|---|---|---|
| 1 | `vmId` 개명(§1 #2) 반영 시점 | 백엔드는 `nodeId` 를 더 받지 않는다(400 필수 파라미터 누락). 프론트 `vmParams` 한 줄 수정과 동시에 배포 |
| 2 | MDC 목록 행 `name` 이 `mdcId` 와 같음 | MDC 표시명 원장이 없어 id 를 이름으로 쓴다. 표시명이 생기면 `name` 만 바뀌고 `mdcId` 는 유지 |
| 3 | HW_*(Temperature·Fan·Power·Voltage·Health·Events) 패널 | 올해 범위 밖 — 트리에는 `NOT_CONFIGURED` 로 나오고 패널 API 는 없다. `unsupported('no-api')` 유지 |
| 4 | 노드 Memory·Network 개요 탭 | 요약 API 를 따로 두지 않는다(§1 #6). 필요하면 `node/{nodeId}/summary` 의 memory·networkTraffic 카드 재사용 |
| 5 | GPU 대시보드 trend `timestamp` 형식(OffsetDateTime) | 자원 탐색기 시계열은 전부 epoch 초다. 대시보드 쪽 통일은 별도 작업으로 진행 여부 결정 대기 |
