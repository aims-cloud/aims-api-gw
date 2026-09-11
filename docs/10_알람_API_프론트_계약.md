# 10. 알람 API 프론트 계약 — 관측 > 알람 (발생 이력 · 정책)

- 작성일: 2026-09-08 (최신화 2026-09-09)
- 대상: 프론트 `src/features/alarm/api/{alarmApi,alarmMapper,alarmQueries}.ts` (specs/010-alarm-management)
- 기준 코드: 백엔드 `feat/event-alarm` — `AlarmHistoryController`, `AlarmPolicyController`
- 관련 문서: docs/09 알람 설계(D부 구현 현황), docs/06 자원 탐색기 트리 설계(역할·자원 영역 식별자)

프론트가 2026-09-08 이전 Swagger 로 만든 API 계층과 현재 백엔드가 다른 곳을 먼저 적고(§1), 그 뒤에 엔드포인트별 계약(§3~§5)과 확인이 필요한 사항(§7)을 정리한다.

## 1. 프론트 코드 대비 바뀐 것 (반영 필요)

| # | 프론트 현재 | 백엔드 현재 | 조치 |
|---|---|---|---|
| 1 | 이력·정책 필터 `roleId`, 요청 body `roleId` | **`roleName`** — 역할 이름 문자열(예: `ADMIN`). 토큰의 역할 이름과 같은 값 | `HistoryListParams.roleId`·`PolicyListParams.roleId`·`AlarmPolicyRequest.roleId` → `roleName`. 역할 옵션은 `GET /api/v1/monitoring/resource-explorer/root/roles` 의 `roleName` 을 그대로 쓴다 |
| 2 | 배포영역 `deploymentId` (필터·응답·요청) | **`areaId`** — 자원 탐색기 트리의 자원 영역 식별자. MDC 는 `mdcId`, Tumblebug Namespace 는 `TB:{nsId}`. 응답에는 `areaKind`(`MDC`\|`NAMESPACE`)와 표시명 `areaName` 도 온다 | `deploymentId` → `areaId`. 배포영역 옵션은 `/api/v1/topology/deploy-zones` 가 아니라 **`GET /api/v1/monitoring/resource-explorer/role/{roleName}/areas`**(역할을 고른 뒤) 의 `areaId`·`name`. 이력 표시는 `areaName`(mapper 의 `deployZone: text(dto.deploymentId)` → `text(dto.areaName)`) |
| 3 | 이력 `keyword` 만 | `searchField`(`ALL`\|`RESOURCE_NAME`\|`POLICY_NAME`, 기본 `ALL`) + `keyword` | 화면의 검색 대상 Select(전체/대상 자원/정책명) 값을 `searchField` 로 전달 |
| 4 | `from`/`to` 형식 미확정(DIFF-003·034) | **`YYYY-MM-DD`** (ISO date). 서버가 KST 기준 `from 00:00 ≤ occurredAt < to+1일 00:00` 으로 해석 — **종료일 포함**. `from > to` 는 400 | `serializeRange` 는 현재 구현 그대로 유효 |
| 5 | 상세 응답에 조건 구조 없음(DIFF-025) → 편집 2단계 재입력 | 상세에 편집 폼 원값 추가: `metricType`, `metric`, `operator`, `threshold`, `targetResourceIds`, `recipients[]`, `enabled`, `areaKind` | `draftFromDetail` 에서 2단계 프리필 가능. `criterion` 파싱 불필요. 수신자·대상도 상세 한 번으로 프리필 가능(별도 API 호출 유지해도 됨) |
| 6 | `metric` 값 = fixture 문자열(`CPU Used(%)`) | **카탈로그 코드**(`CPU_USED_PERCENT` 등, §6). `metricType` 은 서버가 코드에서 유도하므로 보내도 무시 | `mapCatalog` 의 `value: metric` 이 이미 코드값이라 그대로 제출하면 된다 |
| 7 | `resourceType` 값 체계 미확정(DIFF-019), 개별 대상 UI 비활성 | `node`\|`vm`\|`gpu` 소문자. 개별 대상 `targetResourceIds` 는 자원 탐색기와 같은 id — 호스트 `assets.id`(숫자 문자열), VM `TB:{ns}/{infra}/{node}` | 개별 대상 선택기는 자원 탐색기 역할 패널 노드 목록(호스트)·VM 목록의 id 를 쓰면 된다. 1차는 ALL 만 제출해도 무방 |
| 8 | 정책 목록 필터 `roleId`·`deploymentId`·`keyword` | `roleName`, `areaId`, `resourceType`, `resourceId`, `level`, `keyword`(정책명) | 필터 UI 에 있는 만큼 전달 |
| 9 | 조직 트리 3단(DIFF-023) | 1단 — 조직 노드(`orgId` = 조직명 문자열, `children` 빈 배열) → `members[{userId, name}]`. 조직 없는 사용자는 `미지정` | `mapCandidates` 의 1단 렌더 유지. `children` 필드가 응답에 추가됐지만 비어 있다 |
| 10 | 확인 후 재확인 시 처리 없음 | 이미 확인된 알람에 `POST …/acknowledge` → **409** | 409 는 "이미 확인됨"으로 안내 후 확인 내역 재조회 |
| 11 | 이력 행 `deliveryCount` 클릭 → 전송 이력 조회 | `GET …/deliveries`, `…/message` **구현 완료(2026-09-09)**. `deliveryCount` 는 전송 행 수(대상 × 채널) | `AlarmDeliveryResponse`·`AlarmMessageDetailResponse` 를 §3.4 스키마로 재작성. 채널마다 `deliveryId` 가 다르고 `channel` 파라미터는 선택 |
| 12 | 정책 목록에 알림 방식 없음(DIFF-013) | 목록 응답에 `channels[]`(`EMAIL`\|`SMS`) 추가(2026-09-09) | 목록 열에 바로 표시 가능. 상세 재조회 불필요 |

## 2. 공통

- 기본 경로 `/api/v1/monitoring/alarms`, 인증 Bearer JWT.
- 응답 래퍼 `ApiResponse<T> { success, data, message }`. 목록은 `data` 가 `PageResponse<T> { items, page, size, totalElements, totalPages }`, **page 는 1부터**, size 1~100.
- enum 은 전부 대문자 문자열: `level` `INFO|WARNING|CRITICAL`, `operator` `GT|GTE|LT|LTE|EQ`, `channel` `EMAIL|SMS`, `targetType` `INDIVIDUAL|ORGANIZATION`, `targetScope` `ALL|INDIVIDUAL`, `alarmType` `METRIC|LOG`, `source` `POLICY|SYSTEM|EXTERNAL|EVENT`, `status` `OPEN|ACKNOWLEDGED|RESOLVED`, `areaKind` `MDC|NAMESPACE`, `searchField` `ALL|RESOURCE_NAME|POLICY_NAME`, 전송 결과 `SUCCESS|SENDING|FAILED`.
- 정렬 `sort={필드},{asc|desc}`. 허용 필드 밖이면 400(메시지에 가능한 값 나열).
- 오류 상태: 400 검증 실패(메시지 한글), 404 없음 또는 **역할 스코프 밖**(존재를 드러내지 않음), 409 이미 확인된 알람, 501 미구현.
- 역할 스코프: 이력·정책 모두 **토큰에 있는 역할**의 행만 보인다. 이력은 역할 없는 행(시스템·외부 알람)도 보인다. `roleName` 필터에 토큰에 없는 역할을 주면 빈 페이지.
- 날짜·시각은 ISO-8601 offset 문자열(`2026-09-08T09:00:00+09:00`).

### 2.1 403 실측(ALARM-API-UI-DIFF-031)에 대해

백엔드에는 경로별 권한 규칙이 없고(`anyRequest().authenticated()`), 알람 컨트롤러에 `@PreAuthorize` 도 없다. 유효 토큰이면 13개 전부 200/400/404/501 중 하나를 준다. Spring Security 기본 설정은 **인증이 안 된 요청에 401 이 아니라 403** 을 돌려주므로, 전부 403 이면 `Authorization: Bearer …` 헤더가 백엔드에 도달하지 않았거나(프록시·CORS preflight), 토큰이 만료·서명 불일치인 경우다. 확인 순서: ① 같은 토큰으로 `GET /api/v1/monitoring/resource-explorer/root/roles` 도 403 인지(그러면 인증 문제) ② 백엔드 로그에서 요청의 Authorization 헤더 존재 여부. 백엔드는 미인증을 401 로 돌려주도록 엔트리포인트를 바꾸는 것을 검토한다(§7 #6).

## 3. 발생 이력

### 3.1 `GET /histories`

| 파라미터 | 형식 | 설명 |
|---|---|---|
| `level` | 반복 키(`level=WARNING&level=CRITICAL`) | 다중. 없으면 전체 |
| `roleName` | string | 역할 이름. 토큰에 없는 역할이면 빈 페이지 |
| `areaId` | string | `mdcId` 또는 `TB:{nsId}` |
| `resourceType` | `node`\|`vm`\|`gpu` | |
| `resourceId` | string | 자원 탐색기 알람 탭용(호스트 `assets.id` / VM TB id) |
| `policyId` | long | 정책 탭의 발생 이력 |
| `searchField` | `ALL`(기본)\|`RESOURCE_NAME`\|`POLICY_NAME` | keyword 적용 컬럼. ALL = 자원 이름 + 정책명 |
| `keyword` | string | 대소문자 무시 부분 일치 |
| `from`, `to` | `YYYY-MM-DD` | KST, 종료일 포함. `from > to` 400 |
| `page`, `size` | 1부터 / 1~100 | 기본 1 / 10 |
| `sort` | `occurredAt`(기본 desc), `level`, `source`, `status`, `resourceType`, `resourceName`, `criterion`, `observedValue`, `roleName`, `areaId`, `policyName`, `deliveryCount` | |

응답 항목 `AlarmHistoryListItemResponse`

| 필드 | 형식 | 비고 |
|---|---|---|
| `alarmId` | long | |
| `occurredAt` | date-time | |
| `level` | enum | |
| `source` | enum | `POLICY` 만 생성됨(시스템·외부는 예정) |
| `status` | enum | `OPEN`\|`ACKNOWLEDGED`\|`RESOLVED`. 화면에 열이 없으면 무시 가능 |
| `resourceType`, `resourceId`, `resourceName` | string | `resourceName` 을 자원 상세 링크에 |
| `criterion` | string | `CPU Used > 80%` |
| `observedValue` | string | 값+단위(`92%`, `3.3 GB`, `88°C`). 없으면 null |
| `roleName` | string | |
| `areaId`, `areaKind`, `areaName` | string, enum, string | 표시는 `areaName`(MDC 는 mdcId, Namespace 는 Tumblebug 표시명) |
| `policyId`, `policyName` | long, string | |
| `deliveryCount` | long | 전송 행 수(대상 × 채널). 발생 직후 전송 계획과 함께 올라간다 |
| `acknowledged` | boolean | |
| `inhibited` | boolean | 상위 알람에 눌려 전송 억제된 행(억제 구현 전까지 false) |

### 3.2 `POST /histories/{alarmId}/acknowledge`

- body `{ "content": "확인 내용" }` — 공백이면 400.
- 확인자·확인 시각은 서버가 인증 사용자(이메일)·현재 시각으로 기록. 상태 `OPEN → ACKNOWLEDGED`.
- 404 없음·스코프 밖, **409 이미 확인됨**. 응답은 §3.3 과 같은 `AlarmAcknowledgementResponse`.

### 3.3 `GET /histories/{alarmId}/acknowledgement`

- 확인 내역 모달. 미확인 알람이면 404.
- `{ occurredAt, level, resourceType, resourceName, criterion, observedValue, acknowledgedAt, acknowledgedBy(이메일), content }`

### 3.4 `GET /histories/{alarmId}/deliveries`, `GET …/deliveries/{deliveryId}/message?channel=`

전송 원장(`alarm_deliveries`)은 **알람 × 전송 대상(개인/조직) × 채널** 이 1행이라 채널마다 `deliveryId` 가 다르다. 전송 이력은 대상 단위로 묶어 내려주고, 메시지 상세는 채널 행의 `deliveryId` 로 연다. 전송은 **발생** 전이 때만 만들어지고(해소·확인은 전송 없음) 수신자 행은 `SENDING` 으로 시작해 발송 결과로 `SUCCESS`/`FAILED` 가 된다. 스코프 밖·없는 알람은 404, 전송이 없는 알람은 빈 배열.

`GET /histories/{alarmId}/deliveries` → `AlarmDeliveryResponse[]`

| 필드 | 형식 | 비고 |
|---|---|---|
| `targetType`, `targetId`, `targetName` | enum, string, string | 개인 = users.id·이름(삭제됐으면 `(삭제된 사용자)`), 조직 = 조직명 |
| `channels[]` | `ChannelSummary[]` | 정책 채널 순서(EMAIL, SMS) |
| `channels[].deliveryId` | long | 메시지 상세용 |
| `channels[].channel` | `EMAIL`\|`SMS` | |
| `channels[].status` | `SUCCESS`\|`SENDING`\|`FAILED` | **개인만**(조직은 null) |
| `channels[].successCount`, `sendingCount`, `failedCount` | long | **조직만**(개인은 null) |
| `channels[].recipientCount` | int | 전개된 수신자 수(개인은 1) |
| `channels[].sentAt` | date-time | 수신자 중 마지막 시도 시각. 아직 시도 전이면 null |

`GET /histories/{alarmId}/deliveries/{deliveryId}/message?channel=` → `AlarmMessageDetailResponse`

- `channel` 은 **선택**. 주면 전송 행의 채널과 같아야 하고 다르면 400. `deliveryId` 가 이 알람의 것이 아니면 404.

| 필드 | 형식 | 비고 |
|---|---|---|
| `policyName`, `resourceName` | string | 알람 이력 스냅샷 |
| `channel`, `targetType`, `targetName` | | |
| `totalRecipientCount` | int | 조직만(개인은 null) |
| `resultSummary { successCount, failedCount }` | | 조직만(개인은 null). SENDING 은 두 수에 들어가지 않는다 |
| `subject`, `messageBody` | string | 발송 시점 치환 스냅샷. SMS 는 본문만 쓴다 |
| `recipients[] { name, id, address, status, failReason, sentAt }` | | `id` 는 users.id 문자열(삭제된 사용자 null), `address` 는 EMAIL 이메일 / SMS 전화번호(현재 항상 null) |

`failReason` 값: `연락처 없음`(SMS — `users.phone` 도입 전 전부), `삭제된 사용자`, `비활성 사용자`, `IllegalStateException: 메일 서버 미구성 (SPRING_MAIL_HOST)`, 그 외 메일 서버 오류(`MailSendException: …`). 재시도는 수신자당 최대 3회이며 사유는 마지막 시도 기준.

## 4. 정책

### 4.1 `GET /policies`

| 파라미터 | 형식 | 설명 |
|---|---|---|
| `roleName`, `areaId`, `resourceType` | string | §3.1 과 같은 값 체계 |
| `resourceId` | string | 그 자원을 개별 대상으로 고른 정책 + 그 자원을 실제로 덮는 ALL 정책 |
| `level` | enum | 단일 |
| `keyword` | string | 정책명 부분 일치 |
| `page`, `size`, `sort` | | `sort` 허용: `policyName`, `roleName`, `areaId`, `resourceType`, `level`, `createdBy`, `createdAt`(기본 desc). `targetResourceCount` 는 정렬 불가(계산값) |

응답 항목 `AlarmPolicyListItemResponse`: `policyId, policyName, roleName, areaId, resourceType, targetResourceCount, criterion, level, createdBy(표시명), createdAt`. 알림 방식 `channels[]`(`EMAIL`\|`SMS`)도 목록 응답에 포함된다(2026-09-09 추가 — DIFF-013 해소).

### 4.2 `GET /policies/{policyId}`

`AlarmPolicyDetailResponse`

| 필드 | 비고 |
|---|---|
| `policyId`, `alarmType`, `policyName`, `roleName`, `areaId`, `areaKind`, `resourceType`, `targetScope` | `areaId` 는 null 가능(역할 전체) |
| `targetResourceIds[]` | INDIVIDUAL 일 때. ALL 이면 빈 배열 |
| `metricType` | `CPU`\|`Memory`\|`File System`\|`GPU` — 카탈로그 유형 라벨 |
| `metric`, `operator`, `threshold` | 편집 폼 원값. `metric` 은 코드 |
| `criterion`, `level`, `channels[]`, `durationSeconds` | |
| `recipients[]` | `{ targetType, targetId, name }` — 삭제된 사용자는 `name = "(삭제된 사용자)"` |
| `enabled`, `createdBy`(표시명), `createdAt`, `updatedAt`, `description` | |

### 4.3 `POST /policies`, `PUT /policies/{policyId}`

요청 `AlarmPolicyRequest`

```json
{
  "alarmType": "METRIC",
  "policyName": "cpu-high",
  "roleName": "ADMIN",
  "areaId": "mdc-01",
  "resourceType": "node",
  "targetScope": "ALL",
  "targetResourceIds": [],
  "description": "설명",
  "condition": { "metricType": "CPU", "metric": "CPU_USED_PERCENT", "operator": "GT", "threshold": 80, "level": "WARNING" },
  "delivery": { "durationSeconds": 0, "channels": ["EMAIL", "SMS"], "recipients": [
    { "targetType": "INDIVIDUAL", "targetId": "12" },
    { "targetType": "ORGANIZATION", "targetId": "플랫폼팀" } ] }
}
```

검증(400, 메시지 한글)

| 항목 | 규칙 |
|---|---|
| `alarmType` | `LOG` 는 1차 제외 → 400 |
| `policyName` | 필수, 100자 이하 |
| `roleName` | 필수, **토큰에 있는 역할**만 |
| `areaId` | 선택. 없으면 역할 전체. `TB:` 접두면 Namespace |
| `resourceType` | `node`\|`vm`\|`gpu` |
| `targetScope` | 필수. `INDIVIDUAL` 이면 `targetResourceIds` 1개 이상 |
| `condition.metric` | 카탈로그 코드(초안 별칭 `cpu_usage` 등도 허용). 자원 유형에 맞아야 함(vm 에 GPU 메트릭 불가) |
| `condition.operator`, `threshold`, `level` | 필수. threshold 숫자 |
| `delivery.durationSeconds` | 0 이상. 0 = 즉시 |
| `delivery.channels` | 1개 이상 |
| `delivery.recipients` | 1개 이상. `INDIVIDUAL.targetId` = 존재하는 `users.id`, `ORGANIZATION.targetId` = 존재하는 조직명(`미지정` 포함). 같은 쌍은 중복 제거 |
| `description` | 1000자 이하 |

PUT 은 생성과 같은 body 로 전체 교체. 스코프 밖·없는 정책 404. 응답은 §4.2.

### 4.4 `POST /policies/{policyId}/duplicate`

body 없음. `"{이름} (복제)"` 로 새 정책, 복제한 사용자가 생성자. 응답 §4.2.

### 4.5 `DELETE /policies?ids=1,2`

콤마 구분 다중. 하나라도 없거나 스코프 밖이면 404 로 **전체 거부**(부분 삭제 없음). 성공 시 `data: null`.

### 4.6 `GET /policies/{policyId}/resources`

`keyword`(이름·별칭·id 부분 일치), `page`, `size`, `sort`(`name`(기본 asc)\|`alias`\|`resourceId`). 항목 `{ resourceId, name, alias }`. ALL 정책은 역할 스코프에서 전개한 결과, Tumblebug 장애 시 빈 목록.

### 4.7 `GET /policies/{policyId}/recipients`

`[{ targetType, targetId, name }]`. 조직은 `name = targetId`(조직명), 삭제된 사용자는 `(삭제된 사용자)`.

### 4.8 `GET /policies/metric-catalog?resourceType=`

`resourceType` 없으면 전체, `node|vm|gpu` 면 그 자원에 쓸 수 있는 메트릭만, 그 외 400. 응답 `[{ metricType, metrics: [{ metric, displayName, unit }] }]` — 유형 순서 CPU → Memory → File System → GPU. 코드 표는 §6.

### 4.9 `GET /policies/recipient-candidates?keyword=`

`{ organizations: [{ orgId, name, children: [], members: [{ userId, name }] }] }`. `orgId` = 조직명(`users.organization`), 무소속은 `미지정`. `keyword` 는 조직명·이름·이메일 부분 일치(조직이 맞으면 멤버 전체). `userId` 를 수신자 `INDIVIDUAL.targetId`, `orgId` 를 `ORGANIZATION.targetId` 로 제출.

## 5. 옵션(필터·마법사 드롭다운) 원천

| 옵션 | API | 쓰는 값 |
|---|---|---|
| 역할 | `GET /api/v1/monitoring/resource-explorer/root/roles?page=1&size=100` | `items[].roleName`(값), `name`(표시) — 토큰 역할만 온다 |
| 배포영역(자원 영역) | `GET /api/v1/monitoring/resource-explorer/role/{roleName}/areas?page=1&size=100` | `items[].areaId`(값), `name`(표시), `kind` |
| 대상 자원 유형 | 고정 | `node`(노드) / `vm`(가상머신) / `gpu`(GPU) |
| 개별 대상 자원 | 자원 탐색기 역할 패널의 노드·VM 목록 | 노드 `assets.id`, VM `TB:{ns}/{infra}/{node}` |
| 메트릭 유형·메트릭 | §4.8 | |
| 수신자 | §4.9 | |
| 연산자 | 고정 | 초과 `GT`, 이상 `GTE`, 미만 `LT`, 이하 `LTE`(EQ 는 노출 안 함 — 현재 mapper 그대로) |

## 6. 메트릭 카탈로그 코드

| `metric` | `metricType` | `displayName` | `unit` | 자원 유형 |
|---|---|---|---|---|
| `CPU_USED_PERCENT` | CPU | CPU Used | % | node, vm |
| `MEMORY_USED_PERCENT` | Memory | Memory Used | % | node, vm |
| `MEMORY_AVAILABLE_GB` | Memory | Memory Available | GB | node, vm |
| `FS_USED_PERCENT` | File System | FS Used | % | node, vm |
| `INODE_USED_PERCENT` | File System | Inode Used | % | node, vm |
| `GPU_UTIL` | GPU | GPU Util | % | node, gpu |
| `GPU_MEMORY_USED_PERCENT` | GPU | GPU Mem | % | node, gpu |
| `GPU_TEMP` | GPU | GPU Temperature | °C | node, gpu |

기준 문자열은 `displayName + ' ' + 연산자 기호 + ' ' + 임계값 + unit` (`CPU Used > 80%`, `GPU Temperature >= 85°C`). 마법사 요약 화면도 같은 규칙으로 만들면 서버 `criterion` 과 일치한다.

## 7. 확인·결정이 필요한 사항 (프론트 ↔ 백엔드)

| # | 항목 | 백엔드 입장 |
|---|---|---|
| 1 | fixture 의 `CPU Utilization` 메트릭 | **확정: 카탈로그 제외.** `CPU Used(%)` 와 구별되는 정의가 없다. fixture 에서 지우면 되고, 다른 정의(load 기반 등)가 필요해지면 그때 코드 추가 |
| 2 | VM 정책의 GPU 메트릭 | VM↔GPU 할당 원장이 없어 `vm` 에는 GPU 유형이 오지 않는다. 자원 탐색기 VM GPU 패널도 501 |
| 3 | 이력 신규 필드 `source`·`status`·`inhibited`·`areaName` | **백엔드 작업 없음.** 필드는 이미 응답에 있으므로 컬럼 추가 여부는 화면 판단. `areaName` 은 배포영역 열에 쓰는 것을 권장 |
| 4 | 정책 목록에 `channels` 열 | **완료(2026-09-09).** 목록 응답에 `channels[]` 추가(§1 #12, §4.1) |
| 5 | 확인(acknowledge) 409 처리 | "이미 확인됨" 안내 후 확인 내역 재조회 |
| 6 | 미인증 응답 코드 | 현재 403(Spring 기본). 401 로 바꾸면 apiClient 의 토큰 갱신 분기가 명확해진다 — **알람 범위 밖**(전역 Security 엔트리포인트). 인증·RBAC 공통 작업으로 이관 |
| 7 | 전송 이력·메시지 상세 스키마 | **완료(2026-09-09).** §3.4 로 확정 — 수신자별 주소·결과·일시·실패 사유 포함. `channel` 파라미터는 선택으로 완화 |
| 8 | 조직 트리 3단 | 조직 원장(organizations)이 계정 도메인에 생기면 `children` 을 채운다. 그때까지 1단 |
