# 13. 스케줄러 API 프론트 계약 — 지능형 예측 진단 (스케줄러 · 전력 최적화 진단)

- 작성일: 2026-09-10
- 대상 화면: 지능형 예측 진단 > 스케줄러, 지능형 예측 진단 > 전력 최적화 진단
- 기준 코드: `com.innogrid.aims.scheduler` — 10장 변경 이력의 마지막 행 시점. 엔드포인트 77개
- 관련 문서: docs/10 알람 API 프론트 계약(같은 형식), docs/06 자원 탐색기 트리 설계(역할 스코프), docs/14 배치 전략 비교 방법론(8장 비교 API 의 판·지표·판정 규칙이 왜 그렇게 정해졌는지와 기준 시점의 봉인 값)

**필드 하나하나의 진실은 Swagger(`/swagger-ui/index.html`, `/v3/api-docs`)입니다.** 이 문서는 필드를 전수로 옮기지 않습니다. 대신 화면을 붙일 때 Swagger 만으로는 알기 어려운 것 — 경로 묶음, 공통 규칙, 값이 비는 조건, 오류가 나는 조건, 그리고 앞으로 바뀔 예정 — 을 적습니다. 필드 목록이 필요하면 Swagger 를 보고, 이 문서와 다르면 코드가 맞습니다.

---

## 1. 공통

| 항목 | 규칙 |
|---|---|
| 기본 경로 | `/api/v1/scheduler` |
| 인증 | `Authorization: Bearer <JWT>`. 전 경로 인증 필요 |
| 응답 래퍼 | `{ success, data, message }`. 실패면 `success=false` 이고 `message` 에 한국어 사유 |
| 목록 | 페이징은 `data` 가 `{ items, page, size, totalElements, totalPages }`. **`page` 는 1부터**, `size` 는 **최대 100**(기본값은 경로마다 다름 — 스케줄·실행 이력 10, 배치 이력·전달 기록·워크로드 페이징 20) |
| 배열 응답 | 페이징이 아닌 목록은 `data` 가 배열(노드·엔진·제약·이력 등) |
| 정렬 | `sort={필드},{asc|desc}`. 허용 밖이면 400 이고 메시지에 가능한 값을 나열. 값이 같은 행은 `id` 로 한 번 더 정렬됩니다(1.1절 아래 **보조 정렬**) |
| 열거형 | 전부 대문자 문자열. 요청 시 소문자를 받아 주는 자리가 일부 있으나 응답은 항상 대문자 |
| 키 순서 | 스키마에 선언된 필드는 선언 순서로 나옵니다(`details`·`capacityByNode` 처럼 키가 값인 맵 필드는 각 절의 규칙을 따릅니다). 화면은 키 이름으로 읽으십시오 — 응답 타입 명시(1.4절) 전에는 리소스 종류·엔진·냉각 대기의 원소와 일괄 재개 `data` 의 키 순서가 서버를 띄울 때마다 달라질 수 있었습니다(키·값은 같음) |
| 시각 | ISO-8601 offset 문자열(`2026-09-10T09:00:00+09:00`) |
| 기간 `from`·`to` | API 마다 규칙이 다릅니다 — **1.3절** 표. 공통: 값은 오프셋까지 든 ISO-8601(쿼리의 `+` 는 `%2B`) |
| 오류 | 400 검증 실패(사유 한국어), 404 대상 없음, 409 상태 충돌, 429 동시 실행 제한(배치 전략 비교 한 곳만 — 8장). **스케줄러 경로는 필수 파라미터가 빠져도 400 과 사유를 줍니다** |

### 1.1 정렬 허용 필드

| 대상 | 기본값 | 허용 필드 |
|---|---|---|
| 스케줄 목록 | `createdAt,desc` | scheduleId, name, resourceType, cronExpression, description, autoRun, enabled, lastRunAt, lastRunFinishedAt, lastRunStatus, nextRunAt, createdAt, updatedAt |
| 실행 이력 | `startedAt,desc` | startedAt, finishedAt, status, triggerType, targetCount, successCount, failedCount |
| 배치 이력 | `createdAt,desc` | createdAt, finalScore, workloadName, success |
| 전달 기록 | `requestedAt,desc` | id, requestedAt, respondedAt, status, workloadId |
| 워크로드 페이징 | `createdAt,desc` | createdAt, name, status, resourceType, priority, requiredCpu, requiredMemoryMb, requiredGpu, requiredDiskGb, retryCount, scheduledAt, lastFailedAt |

- **보조 정렬.** 페이징 다섯 곳(스케줄 목록 · 실행 이력 · 배치 이력 · 전달 기록 · 워크로드 페이징) 모두 값이 같은 행을 `id` 로 한 번 더 정렬합니다(방향은 요청 정렬을 따름 — 최신순이면 `id` 내림차순, `createdAt,asc`·`enabled,asc`·방향 생략(`sort=enabled`)이면 `id` 오름차순이라 같은 값 안에서 오래된 행이 먼저입니다. 알람·포털 로그 목록은 방향과 무관하게 `id` 내림차순이라 다릅니다). 그래서 `status`·`success`·`enabled`·`priority` 처럼 동률이 많은 필드로 정렬해도, 조회 사이에 데이터가 바뀌지 않는 한 페이지 경계에서 행이 겹치거나 빠지지 않습니다. `id` 를 정렬 필드로 받는 곳은 전달 기록뿐이고(스케줄 목록의 `scheduleId` 는 사용자가 정한 문자열 키로 `id` 가 아닙니다), 요청에 `id` 가 이미 있으면 보조 키를 덧붙이지 않습니다. 실행 상세의 `decisions[]` 도 같은 시각이면 `id` 오름차순이라 배치 이력 페이징 `sort=createdAt,asc` 와 행 순서가 같습니다(3장 **실행 필터** 의 「정렬·크기」).
- **워크로드 페이징의 정렬 단서.** `priority` 는 DB 에 이름 문자열로 저장되지만 서버가 **등급 순(LOW → NORMAL → HIGH → CRITICAL)** 으로 바꿔 정렬합니다 — `sort=priority,desc` 면 CRITICAL 먼저, 방향 생략·asc 면 LOW 먼저. 같은 등급 안은 `id` 보조 정렬입니다 — 다른 필드와 같은 규칙(위 **보조 정렬**)을 따르며 `priority` 만의 예외는 두지 않았으니, desc 면 같은 등급 안에서 최신 먼저, asc 면 오래된 것 먼저입니다. `status=PENDING,FAILED&retryExhausted=false&resourceType={스케줄의 종류}&sort=priority,desc` 는 스케줄 실행이 대상을 고르는 순서(등급 내림차순 → 오래된 것 먼저)의 **근사**입니다 — 같은 등급 안의 방향이 반대이고, 재시도 간격 대기 행이 섞이며, 크기 우선 스케줄은 순서가 다르고, 한 회차는 앞에서부터 설정 상한(기본 500)건까지만 처리합니다. `sort` 는 키 하나만 받아 실행 순서 그대로를 한 번의 호출로는 만들 수 없습니다 — 그대로가 필요하면 등급별로 `priority=CRITICAL`(→ `HIGH` → `NORMAL` → `LOW`)`&sort=createdAt,asc` 를 차례로 불러 이어 붙이고, 대기 행은 `/workloads/retry-policy` 의 `retryBackoffMinutes` 와 행의 `lastFailedAt` 으로 화면에서 걸러 표시하십시오(페이지 번호가 등급마다 따로). 필터(3장 주의 12)와 함께 쓸 수 있습니다. `status`·`resourceType` 은 등급 변환 없이 사전순이며 같은 값끼리 묶는 용도입니다. `resourceType`·`retryCount`·`scheduledAt`·`lastFailedAt` 는 비어 있을 수 있고, null 행은 배포 DB(PostgreSQL)에서 asc 면 맨 뒤, desc 면 맨 앞입니다(로컬 H2 는 반대). `retryCount` 의 null 은 0회로 읽되 asc 에서 1회 이상인 행보다 뒤에 옵니다.

### 1.2 상태 값

| 대상 | 값 |
|---|---|
| 워크로드 | `PENDING` `SCHEDULED` `RUNNING` `COMPLETED` `FAILED` |
| 실행 | `RUNNING` `COMPLETED` `FAILED` · 실행 주체 `AUTO` `MANUAL` |
| 배치 결정 전달 | `REQUESTED` `ACCEPTED` `RUNNING` `COMPLETED` `FAILED` `REJECTED` `UNAVAILABLE`(실행 계층에 닿지 못했거나 결과를 모름 — 재시작 때 전달 중이던 기록도 이 값으로 닫힘 · 7장) `TIMED_OUT`(원격 모드 전용 — 지금의 record 모드에서는 나오지 않음) |
| 리소스 종류 | `VM` `GPU` `NPU` `CONTAINER` |
| 배치 순서 | `PRIORITY_THEN_AGE` `PRIORITY_THEN_SIZE` |
| 엔진 | `weighted` `power` `temperature` (소문자 — 엔진 식별자라 예외) |

### 1.3 기간 파라미터

| API | 기준 시각 | 구간 | 둘 다 비움 | 한쪽만 비움 | 400 |
|---|---|---|---|---|---|
| 배치 이력 페이징 `/history/page` | 결정 `createdAt` | `[from, to)` | 창 없음 | 비운 쪽 경계 없음 | 시작이 끝보다 뒤 |
| 제외 사유 합계 `/history/exclusion-summary` | 결정 `createdAt` | `[from, to)` | 최근 30일 | 비운 쪽 경계 없음 | 시작이 끝보다 뒤 |
| 실행 이력 `/schedules/{id}/runs` | 시작 `startedAt` | `[from, to)` | 창 없음 | 비운 쪽 경계 없음 | 시작이 끝보다 뒤 |
| 일괄 재개 `/workloads/resume-exhausted` | 마지막 실패 `lastFailedAt` | `[from, to]` 양끝 포함 | 리소스 종류 필수(종류도 없으면 400) | 비운 쪽 경계 없음(기간을 하나라도 주면 마지막 실패 시각이 없는 옛 행은 빠짐) | 시작이 끝보다 뒤 |
| 노드 효율 추세 `/nodes/{nodeId}/power-efficiency/trend` · 전력 최적화 추세 `/optimization/power/trend` | 조회 구간 | `from` ~ `to`, `step` 간격 | `to` = 지금 · `from` = `to` − 24시간 | `to` 비움 = 지금 · `from` 비움 = `to` − 24시간 | **시작이 끝과 같아도 400** · 지점 수 상한(`aims.scheduler.power.trend-max-points`, 기본 2000) 초과 |

### 1.4 응답 스키마 이름

작업 10개의 200 응답이 Swagger 에서 임의 객체(`ApiResponseObject` — `data` 의 형태가 없음)로 보이던 것을 실제 응답 타입으로 고쳤습니다. 응답의 키 집합과 값은 그대로이고, 리소스 종류·엔진·냉각 대기의 원소와 일괄 재개 `data` 의 키 순서가 아래 스키마의 선언 순서로 고정됐습니다. Swagger 로 타입을 생성해 쓰는 화면은 다시 생성하면 `data` 가 구체 타입으로 좁혀지고, 기존 스키마의 이름과 내용은 바뀌지 않습니다. 생성 모델에는 새 타입 28개(아래 중첩 이름 포함)가 더해지며, 새 스키마에는 `required` 가 없어 생성된 필드는 모두 선택입니다.

| 메서드 | 경로 | 이전 | 이후 |
|---|---|---|---|
| GET | `/workloads/resource-types` | `ApiResponseObject` | `ApiResponseListResourceTypeOptionResponse` — `[{code, label}]` |
| POST | `/workloads/resume-exhausted` | `ApiResponseObject` | `ApiResponseResumeExhaustedResponse` — `{resumedCount, workloads}` |
| POST | `/workloads/{id}/schedule` | `ApiResponseObject` | `ApiResponseSchedulingResult`(저장 전 모의 배치와 같은 스키마) |
| GET | `/nodes/power-efficiency` | `ApiResponseObject` | `ApiResponsePowerEfficiencyResponse` |
| GET | `/nodes/{nodeId}/power-efficiency/trend` | `ApiResponseObject` | `ApiResponsePowerEfficiencyTrendResponse` |
| GET | `/constraints` | `ApiResponseObject` | `ApiResponseListConstraintDescriptionResponse` |
| GET | `/thermal/cooldowns` | `ApiResponseObject` | `ApiResponseListThermalCooldownResponse` — `[{mdcId, nodeId, temperatureCelsius, thermalState, remainingCooldownMinutes}]` |
| GET | `/engines` | `ApiResponseObject` | `ApiResponseListEngineResponse` — `[{id, name}]` |
| GET | `/optimization/power` | `ApiResponseObject` | `ApiResponsePowerOptimizationResponse` |
| GET | `/optimization/consolidation` | `ApiResponseObject` | `ApiResponseConsolidationResponse` |

전력 효율·전력 효율 추세·전력 최적화·통합 진단 네 응답의 중첩 스키마 이름은 응답 이름에서 끝의 `Response` 를 뺀 것에 중첩 이름을 붙입니다 — `PowerEfficiencySummary`·`PowerEfficiencyNodeEfficiency`, `PowerEfficiencyTrendPoint`, `PowerOptimizationSummary`·`PowerOptimizationMdc`·`PowerOptimizationCluster`·`PowerOptimizationFinding`, `ConsolidationCandidate`·`ConsolidationSummary`·`ConsolidationMove`. 전부터 있던 `Point`(전력 최적화 추세 지점)와 `Candidate`(익스포터 인스턴스 후보)는 이름도 내용도 그대로입니다.

처음 공개할 때 아래 다섯은 단순 이름이었으나, 다른 응답이 같은 이름을 공개하면 Swagger 에 한쪽 내용만 남기 때문에 이름을 바꿨습니다. 필드와 응답 JSON 은 같고, Swagger 로 타입을 생성해 쓰는 화면은 다시 생성하면 타입 이름만 바뀝니다. 옛 이름을 가져다 쓰던 코드는 아래 표대로 이름을 바꿔야 합니다.

| 이전 | 이후 | 위치 |
|---|---|---|
| `NodeEfficiency` | `PowerEfficiencyNodeEfficiency` | 전력 효율 `nodes[]` |
| `Mdc` | `PowerOptimizationMdc` | 전력 최적화 `mdcs[]` |
| `Cluster` | `PowerOptimizationCluster` | 전력 최적화 `cluster` |
| `Finding` | `PowerOptimizationFinding` | 전력 최적화 `findings[]` |
| `Move` | `ConsolidationMove` | 통합 진단 `candidates[].moves[]` |

---

## 2. 스케줄 — `/api/v1/scheduler/schedules` (15)

배치 스케줄 화면입니다.

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET | `/` | 목록. `keyword`, `resourceType`, `enabled`, `page`, `size`, `sort`(필터 규칙은 아래 주의 10) |
| GET | `/{id}` | 상세 |
| POST | `/` | 생성 |
| PUT | `/{id}` | 편집(부분 갱신 · 정책·엔진·상한·설명은 null 로 비움 · 아래 주의 13) |
| DELETE | `/?ids=1,2,3` | 삭제 |
| GET | `/{id}/preview` | 실행 미리보기 — 지금 실행하면 적용될 정책·엔진과 대상·대기·소진 건수(저장 없음 · 아래 주의 11) |
| POST | `/preview` | 저장 전 미리보기 — 생성 폼 값으로 같은 미리보기(저장 없음 · 아래 주의 12) |
| POST | `/dry-run` | 저장 전 모의 실행 — 생성 폼 값으로 주의 14 와 같은 모의 실행(저장 없음 · 아래 주의 15) |
| POST | `/{id}/dry-run` | 모의 실행 — 지금 실행하면 어떻게 배치될지를 실행과 같은 조건으로 저장 없이(아래 주의 14) |
| POST | `/{id}/run` | 즉시 실행 |
| GET | `/{id}/runs` | 실행 이력(페이징). `status`, `triggerType`, `from`, `to` 필터(아래 주의 9) |
| GET | `/{id}/runs/{runId}` | 실행 상세 — 그 실행의 배치 결정 목록과 실행 전후 클러스터 상태 포함 |
| GET | `/{id}/runs/{runId}/replay` | 실행 재생 — 저장한 입력으로 그 실행의 배치를 다시 계산해 배치 이력과 대조(검증용 · 화면 미사용 · 아래 「실행 재생」) |
| GET | `/{id}/runs/{runId}/replay/engines` | 엔진별 재계산 — 저장한 입력 하나로 운영 엔진 세 개를 각각 다시 계산한 워크로드별 선택(검증·연구용 · 화면 미사용 · 아래 「엔진별 재계산」) |
| POST | `/cron/validate` | 실행 주기 검증 + 다음 실행 5건 미리보기 |

**화면을 붙일 때 주의할 것**

1. **삭제는 요청 본문이 아니라 쿼리 파라미터**입니다. `DELETE /schedules?ids=1,2,3`.
2. **생성 필수는 셋뿐**입니다 — 스케줄 ID, 스케줄명, 실행 주기. 나머지는 모두 선택입니다. 리소스 종류를 비우면 빈 채로 저장되고(응답 `resourceType: null`) 실행 때 VM 으로 해석하며, 목록의 `resourceType=VM` 필터에도 포함됩니다(주의 10).
3. **스케줄 ID 는 편집에서 바꿀 수 없습니다.** 보내려면 기존 값과 같아야 하고 다르면 400 입니다(편집의 칸 규칙은 주의 13).
4. **다음 실행 시각은 자동 실행과 사용 여부가 둘 다 켜져 있을 때만** 값이 있습니다. 하나라도 꺼져 있으면 `null` 입니다.
5. **실행 주기는 여섯 자리**(초 분 시 일 월 요일)이고 **요일 숫자가 Quartz 와 한 칸 다릅니다.** 화면에서 만든 값은 `cron/validate` 로 다음 실행 시각을 먼저 보여 주는 편이 안전합니다.
6. 시간대는 서버 설정값이며 기본은 `Asia/Seoul` 입니다. 실행 주기 검증 `POST /cron/validate` 응답의 `timezone` 을 그대로 쓰십시오(스케줄 목록·상세 응답에는 없습니다).
7. **실행 전후 클러스터 상태(`clusterSnapshot`)는 실행 상세에만 있습니다.** 실행 이력 목록 1행에는 없습니다 — 목록은 한 화면에 수십 행이 뜨는 자리라 행마다 열 12개를 더하지 않았습니다.
8. **실행 상세의 결정 목록(`decisions[]`)에도 `exclusionCodes` 가 있습니다** — 형식·읽는 법·키 위치는 3장 **`exclusionCodes`(배치 이력 · 실행 상세 결정)** 과 같습니다. 실행 이력 목록(`/{id}/runs`)과 즉시 실행 응답에는 없습니다. 같은 결정을 배치 이력 쪽에서 보려면 `/history/page?scheduleRunId={runId}` 를 쓰십시오 — 모집단이 같고, 결정의 `historyId` 가 이력 행의 `id` 와 같은 값이라 두 화면을 행 단위로 이을 수 있습니다(정렬·페이지 크기 차이는 3장 **실행 필터(`/history/page?scheduleRunId=`)**).
9. **실행 이력 목록은 `status`·`triggerType`·`from`·`to` 로 거를 수 있습니다.** 전부 선택이고 AND 로 겹치며 총건수(`totalElements`)는 필터를 반영합니다. `status` 는 `RUNNING`·`COMPLETED`·`FAILED`, `triggerType` 은 `AUTO`(주기 실행)·`MANUAL`(즉시 실행) — 대문자 그대로이고 다른 값은 400 에 가능한 값 목록이 실립니다(빈 값은 필터 없음). 정렬·페이징과 함께 씁니다(예: 실패한 실행만 오래된 순 `status=FAILED&sort=startedAt,asc`).
   `from`·`to` 는 **시작 시각 `startedAt`** 의 창으로 `[from, to)` 반개구간·기본 창 없음·비운 쪽은 경계 없음이며, 값은 오프셋까지 든 ISO-8601(쿼리의 `+` 는 `%2B`)이고 오프셋이 없거나 형식이 틀리거나 시작이 끝보다 뒤면 400 입니다 — 배치 이력 페이징과 같은 규칙(3장 주의 5).
   **대상 0건 실행의 유무.** 자동 실행이 대상 0건으로 끝난 경우는 원래 이력이 없으니 필터로도 나오지 않습니다(실행 전에 사유를 미리 보려면 `/{id}/preview`, 주의 11) — 재시도 소진으로 실패한 자동 실행도 그래서, 스케줄의 `lastRunStatus` 가 `FAILED` 인데 `status=FAILED` 목록이 비어 있을 수 있습니다(사유는 스케줄 상세의 `lastRunMessage`). 즉시 실행은 0건이어도 행이 남습니다 — `COMPLETED`(메시지 "대상 없음…"), 재시도 소진이면 `FAILED`. 차례를 기다리는 사이 꺼지거나 지워져 돌지 않은 자동 실행 회차도 이력이 없습니다(주의 13).
10. **스케줄 목록 필터.** `keyword` 는 스케줄 ID·스케줄명·설명의 부분 일치입니다(앞뒤 공백 제거 · 대소문자 무시(영문 기준) · `%` 와 `_` 는 문자 그대로 찾음). `resourceType`(`VM`·`GPU`·`NPU`·`CONTAINER` · 한 번에 한 값 · 대문자 그대로이고 다른 값은 400 에 가능한 값 목록 — 대소문자를 무시하는 워크로드 페이징과 다름)의 **`VM` 은 종류를 비워 둔 스케줄도 포함**합니다 — 실행이 비어 있는 종류를 VM 으로 해석하는 것과 같은 규칙이고(3장 주의 12 워크로드 페이징과 같음), 응답의 `resourceType` 은 원값(`null`) 그대로이니 표에는 VM 으로 표기해도 됩니다. 네 종류 필터의 건수를 더하면 전체와 같습니다. 세 필터(`keyword`·`resourceType`·`enabled`)는 전부 선택이고 AND 로 겹치며 총건수(`totalElements`)는 필터를 반영합니다. 빈 값은 필터 없음입니다.
11. **실행 미리보기 `GET /{id}/preview` 는 "지금 실행하면" 을 저장 없이 보여 줍니다.** 응답 키는 열네 개 순서 고정 — `id`·`scheduleId`·`enabled`·`resourceType`(항상 값 있음 — 비운 스케줄은 `VM`. 스케줄 응답의 원값 `null` 과 다름, 주의 10)·`policyName`·`policySource`(`SCHEDULE`·`RESOURCE_TYPE`·`COMMON`·`DEFAULT` = 5장 선택 순서 ①~④)·`engine`·`engineSource`(`SCHEDULE`·`POLICY`·`DEFAULT`)·`targetCount`·`runLimit`(실행 1회 상한 설정값)·`waitingCount`(재시도 대기, 3장 주의 6)·`exhaustedCount`(재시도 소진, 3장 주의 7)·`emptyReason`·`emptyMessage`. `targetCount` 는 같은 종류(VM 은 종류를 비운 워크로드 포함 · GPU·NPU·CONTAINER 는 미포함 — 대기·소진도 같은 규칙)의 `PENDING` 과 간격이 지난 소진 전 `FAILED` 의 수이며 상한 적용 전 값입니다(넘으면 실행 이력의 `targetCount` 는 `runLimit`). **시도할 건수이지 성공할 건수가 아닙니다** — 대상이 있어도 노드가 없거나 전부 배치에 실패하면 실행은 `FAILED` 로 끝나며, 어떻게 배치될지는 모의 실행(주의 14)으로 알 수 있습니다. 대상이 있으면 `emptyReason`·`emptyMessage` 는 `null` 이고, 0건이면 `EXHAUSTED` > `WAITING` > `NONE` 순으로 하나가 실리며(실행과 같은 우선순위 · 0건 실행의 상태는 `EXHAUSTED` 면 `FAILED`, 나머지는 `COMPLETED`) `emptyMessage` 는 실행이 `lastRunMessage` 에 남길 문구와 글자 단위로 같습니다. **화면 분기는 `emptyReason` 으로 하십시오** — `emptyMessage` 는 표시용이며 문구가 바뀔 수 있습니다. `engine` 은 실행 이력의 `engineId` 와 같은 값입니다(스케줄에 저장된 엔진이 등록 해제된 값이면 그 값이 실리고 실제 배치는 서버 기본 엔진으로 대체됩니다). 노드는 모으지 않고(메트릭 저장소가 아파도 응답) 실행 권한도 잡지 않으며, 정책이 하나도 없어 `default` 를 만들어야 하는 경우에도 미리보기는 만들지 않습니다(실행은 만듭니다, 5장). 대상 수가 많으면 실행과 같은 질의 비용이 들므로 짧은 주기 폴링에는 맞지 않고, 대기·소진 건수는 재시도 간격 경계를 지나며 바뀌므로 호출 시각의 값입니다. 사용 여부가 꺼진 스케줄과 실행 중인 스케줄도 200 이고(`enabled` 로 구분), 스케줄이 없거나 스케줄이 지정한 정책이 지워졌으면 404 입니다(사용 여부가 꺼진 스케줄도 404 — 즉시 실행은 이때 사용 여부 검사가 먼저라 409 입니다).
12. **저장 전 미리보기 `POST /preview` 는 생성 폼의 값으로 주의 11 과 같은 미리보기를 저장 없이 돌려줍니다.** 본문은 생성 요청(`ScheduleRequest`)과 같은 열두 키이고, 응답은 주의 11 과 같은 열네 키·같은 순서입니다 — `id` 는 저장하지 않았으니 `null`, `scheduleId` 는 보낸 값(앞뒤 공백 제거 · 없거나 공백뿐이면 `null`), `enabled` 는 보낸 값(없으면 `true`)입니다.
   **결과를 바꾸는 칸은 셋**입니다 — `resourceType`(없으면 `VM`)·`policyId`(없으면 종류별 활성 → 공통 활성 → `default`)·`engine`(없거나 공백이면 정책 기본 엔진 → 서버 기본). `maxTemperatureCelsius`·`placementOrder` 는 실행과 같은 규칙으로 해석하지만 응답 값에는 드러나지 않습니다. 스케줄명·실행 주기·자동 실행·전력 상한은 보지 않고(전력 상한은 실행 때 노드를 모은 뒤 노드 단위로 거는 제약이라 건수에 닿지 않으며 주의 11 도 같습니다), 설명은 결과에 쓰지 않되 300자 상한만 검사합니다. 그래서 **폼을 채우는 중에도 부를 수 있고** 실행 주기는 `POST /cron/validate` 로 따로 확인하십시오. `scheduleId` 의 형식·중복과 실행 주기는 검사하지 않으므로 **200 이 나와도 저장이 성공한다는 보장은 아닙니다**(생성 때 400·409).
   **편집 폼에서는 병합한 전체 값을 보내십시오.** 편집 `PUT /{id}` 와 달리 키를 빼면 "기존 값 유지" 가 아니라 기본값으로 해석합니다. 편집 폼에서 정책·엔진·상한·설명을 비운 초안은 그 칸에 `null` 을 보내는 편집으로 그대로 저장할 수 있습니다(주의 13).
   **오류 순서.** 본문 없음 400 → 본문 읽기 단계 400(모르는 리소스 종류 — 대문자만 받음 · 깨진 JSON · 타입 오류 · 문구 "요청 본문을 읽을 수 없습니다: …") → 형식 400(모르는 엔진 · 모르는 배치 순서 · 설명 300자 초과) → 없는 `policyId` 404(주의 11 과 같은 문구). 빈 본문 `{}` 는 200 입니다. 주의 11 과 똑같이 노드를 모으지 않고 실행 권한을 잡지 않으며, 정책이 하나도 없어도 `default` 를 만들지 않습니다. 옛 서버에는 이 메서드가 없어 403 입니다.
13. **편집 `PUT /{id}` 는 정책·엔진·전력 상한·온도 상한·설명 다섯 칸을 `null` 로 비울 수 있습니다.** 본문은 편집 요청(`ScheduleUpdateRequest`)이고 키 이름은 생성 요청과 같은 열두 개입니다. 보낸 키만 바꾸고 **키를 빼면 유지**, `policyId`·`engine`·`maxPowerBudgetWatts`·`maxTemperatureCelsius`·`description` 은 **`null` 을 보내면 비웁니다** — 정책과 온도 상한은 상속(5장 선택 순서 · 정책 값)으로, 전력 상한은 제약 없음으로 돌아가고, 비운 뒤 실행 미리보기(주의 11)의 `policySource`·`engineSource` 가 `SCHEDULE` 에서 상속 단계로 바뀝니다. 엔진은 공백 문자열도 비움이고, 설명은 빈 문자열도 비움이며 앞뒤 공백을 지웁니다. 숫자 세 칸(`policyId`·`maxPowerBudgetWatts`·`maxTemperatureCelsius`)은 빈 문자열 `""` 도 400 이니, 비울 때는 반드시 `null` 을 보내십시오. 이 다섯 칸은 JSON 타입도 검사해 숫자 칸에 `"70"` 같은 숫자 문자열, 정책 id 에 소수·문자열, `engine`·`description` 에 숫자를 보내면 400 입니다(생성은 숫자 문자열을 받아 줌). 나머지 일곱 칸(`scheduleId`·`name`·`cronExpression`·`resourceType`·`autoRun`·`enabled`·`placementOrder`)의 `null` 은 유지이고 비울 수 없습니다 — 배치 순서를 기본으로 되돌리려면 `age` 를, 리소스 종류는 `VM` 을 보내십시오. 상태·실행 기록·시각 키는 무시하므로 **상세 조회 응답의 `data` 를 그대로 보내면 수정 기록 외에는 바뀌지 않습니다**(저장된 엔진이 등록 해제된 값이면 엔진 검증에 걸려 400 이니, 이때는 `engine` 을 빼거나 `null` 로 보내십시오 — 주의 11). 정책 id 의 존재는 검사하지 않습니다(실행 때 404 · 생성과 같음).
   **이전과 달라진 점.** 지금까지는 편집에서 `null` 을 "유지" 로 읽어 지정 정책·엔진·상한을 비울 길이 없었고, 참조 스케줄이 있는 정책을 지우려면(409) 스케줄의 정책을 다른 값으로 바꾸거나 스케줄을 지워야 했습니다. 이제 `policyId` 에 `null` 을 보내 참조를 풀 수 있고, 참조가 남아 있으면 삭제는 지금도 409 입니다. 이제 `null` 은 다섯 칸에서 "비움" 입니다. **화면에 없는 칸까지 `null` 로 채운 전체 본문을 보내던 클라이언트는 그 칸이 비워지므로**, 바꾸지 않을 칸은 키를 빼거나 조회한 값을 그대로 넣어 보내십시오. 저장 전 미리보기(주의 12)에 비운 초안을 보낸 결과는 같은 칸에 `null` 을 보내는 편집으로 그대로 저장됩니다.
   **오류 순서.** 본문 읽기 단계 400(모르는 리소스 종류 — 대문자만 받음 · `autoRun`·`enabled` 에 불리언으로 읽을 수 없는 값(예: `"yes"`) · 깨진 JSON · 문구 "요청 본문을 읽을 수 없습니다: …" — 스케줄 조회보다 앞서 없는 id 에도 400) → 없는 스케줄 404 → 본문 없음 400("요청 본문이 필요합니다") → 스케줄 ID 불일치 400 → 칸 400(타입 · 모르는 엔진 · 길이 · 실행 주기 · 배치 순서). 칸 400 이 하나라도 나면 어떤 칸도 바뀌지 않습니다. 실행 주기나 사용 여부를 바꾸면 다음 실행 시각을 다시 계산하는 규칙(주의 4)은 그대로이고, 비우기도 편집이라 수정자·수정 시각이 찍힙니다. 옛 서버는 다섯 칸의 `null` 을 유지로 읽습니다.
   **실행과 겹칠 때.** 편집은 최근 실행 네 칸(`lastRunStatus`·`lastRunAt`·`lastRunFinishedAt`·`lastRunMessage`)을 쓰지 않으므로, 실행 중에 편집해도 실행 중 표시가 풀리지 않고 실행이 끝난 직후의 편집이 표시를 실행 중으로 되돌리지 않습니다. 전에는 편집이 읽은 시점 값으로 네 칸을 다시 써서 실행 권한이 풀리거나(즉시 실행·삭제가 409 대신 통과) 끝난 실행이 기동 정리 때까지 실행 중으로 남을 수 있었습니다. 편집 응답의 이 네 칸은 편집이 읽은 시점 값이니 실행 상태는 상세 조회로 확인하십시오.
   **자동 실행이 차례를 기다리는 동안.** 자동 실행은 차례가 와서 시작하기 직전에 스케줄을 다시 읽어 그 시점 값으로 돕니다. 앞 실행이 길어 차례를 기다리는 사이 사용 여부나 자동 실행을 끄거나 스케줄을 지우면 그 회차는 돌지 않고 실행 이력·최근 실행 네 칸·다음 실행 시각 어느 것도 건드리지 않으며(다음 실행 시각은 편집이 둔 값 그대로 — 주의 4), 리소스 종류·배치 순서·정책·엔진·전력 상한·온도 상한을 바꾸면 바뀐 값으로 돕니다. 실행 주기를 바꿔도 기다리던 회차는 돕니다(편집이 다시 계산한 다음 실행 시각이 미래여도 그렇습니다) — 다음 실행 시각은 그 회차가 끝날 때 새 주기로 계산합니다. 이미 시작한 실행은 시작 시점 값으로 끝까지 가고 다음 실행 시각만 끝날 때 현재 값으로 계산합니다. 전에는 예정 시각을 확인한 시점의 값으로 돌아 그 사이 끈 스케줄도 한 번 더 돌고 바뀐 값이 그 회차에 반영되지 않았습니다. 즉시 실행은 그대로입니다(사용 안 함 409).
14. **모의 실행 `POST /{id}/dry-run` 은 "지금 실행하면 어떻게 배치될지" 를 저장 없이 돌려줍니다.** 실행과 같은 코드로 정책·엔진·대상·처리 순서·실행 1회 상한·전력 상한·온도 상한을 해석하고, 노드 상태 하나 위에 대상을 차례로 놓아 봅니다 — 앞 건이 잡은 예약이 뒤 건에 보이므로 워크로드별 모의 배치(3장 주의 3)를 따로 돌린 것과 결과가 다를 수 있습니다. 실행 계층에는 전달하지 않고 **전부 받는다고 보고** 계산하며, 실행 권한을 잡지 않고 워크로드·배치 이력·전달 기록·실행 이력·`default` 정책 어느 행도 쓰지 않습니다(5장 ④ 단계에서 `default` 행이 없으면 미리보기처럼 저장하지 않은 기본값으로 계산). 응답 키는 열일곱 개 순서 고정 — `id`·`scheduleId`(미리보기와 같은 표시용 문자열 키 · 즉시 실행 응답의 숫자 `scheduleId` 와 다름)·`enabled`·`resourceType`·`policyName`·`policySource`·`engine`·`engineSource`(주의 11 과 같은 값)·`status`(`COMPLETED`/`FAILED` — 한 건이라도 배치되면 `COMPLETED`)·`message`(전부 받았을 때 실행이 `lastRunMessage` 에 남길 문구와 글자 단위로 같음 · 다만 평가 중 예외가 나면 모의는 그 건만 실패(사유 "평가 중 오류: …")로 두고 계속하고, 실행은 그 회차 전체를 "실행 중 오류: …" 로 끝내므로 이때는 다름)·`emptyReason`(대상 0건일 때만 · 화면 분기용)·`targetCount`(**실행 1회 상한을 적용한 뒤** 건수 — 상한 적용 전 건수를 주는 미리보기의 `targetCount` 와 다름)·`successCount`·`failedCount`·`truncated`·`clusterSnapshot`(실행 상세와 같은 열두 칸 · 상태는 `OK`·`NO_TARGET`·`NO_NODES` 중 하나 · "후" 는 모의가 잡은 예약을 반영한 값)·`decisions`. `decisions[]` 한 건은 여덟 키 — `workloadId`·`workloadName`·`success`·`selectedMdcId`·`selectedNodeId`·`finalScore`·`reason`·`exclusionCodes`(실행 상세 결정과 같은 이름·순서 · 실행 상세 결정의 이력 PK·후보 수·엔진·정책·결정 시각은 없음 — 엔진·정책은 응답 위쪽에 한 번 · 후보 점수 목록은 싣지 않으므로 한 건을 자세히 보려면 3장 주의 3). 대상이 0건이면 노드를 모으지 않고 `NO_TARGET` 이며, 노드를 한 대도 모으지 못하면 `FAILED`·`NO_NODES`·빈 `decisions` 이고 `failedCount` 는 `targetCount` 와 같습니다(실행 이력은 배치를 시도하기 전에 끝나 `failedCount` 가 0). 사용 여부가 꺼진 스케줄과 실행 중인 스케줄도 200 이고(`enabled` 로 구분 · 실행 중이면 그 시점의 DB 상태 기준), 스케줄이 없거나 지정한 정책이 지워졌으면 404 입니다. 메트릭을 한 번 수집하므로(최대 수십 초) 버튼 단위로 호출하고, 200 이 이후 실제 실행의 결과를 보장하지는 않습니다(호출 시점 값). 온도 인지를 켠 정책(기본값)이면 온도 상한을 넘은 노드에 냉각 대기 표시가 실제 실행과 같이 남습니다. 이 표시는 그 온도 상한 이하로 평가하는 배치만 막습니다(3장 주의 16). 사용 안 함 스케줄로 불러도 같으며, 표시는 메모리에만 있어 재기동하면 사라집니다. 저장하지 않은 폼으로는 주의 15 의 저장 전 모의 실행을 씁니다. 옛 서버에는 이 경로가 없어 403 입니다.
15. **저장 전 모의 실행 `POST /dry-run` 은 생성 폼의 값으로 주의 14 와 같은 모의 실행을 저장 없이 돌려줍니다.** 본문은 생성 요청(`ScheduleRequest`)과 같은 열두 키이고 응답은 주의 14 와 같은 열일곱 키·같은 순서입니다 — `id` 는 저장하지 않았으니 `null`, `scheduleId` 는 보낸 값(앞뒤 공백 제거 · 없거나 공백뿐이면 `null`), `enabled` 는 보낸 값(없으면 `true`)입니다. 검증과 기본값 해석은 주의 12 와 같습니다(본문 없음 400 → 엔진·배치 순서·설명 400 → 없는 정책 id 404 · 키를 빼면 VM·사용 중·정책 상속). 전력 상한은 폼 값을 그대로 적용합니다(저장 전 미리보기와 달리 노드 단위로 걸림). 온도 상한(`maxTemperatureCelsius`)도 폼 값을 적용하되, **온도 상한을 지정하면 냉각 대기를 새로 걸지 않습니다** — 폼 값은 저장하지 않은 값이라, 그 상한으로 냉각 대기(주의 14 · 그 상한 이하로 평가하는 배치를 막음 · 3장 주의 16)를 남기면 저장하지 않은 폼이 실제 배치를 막기 때문입니다. 이미 걸린 냉각 대기는 상한별로 그대로 봅니다. 결과는 같은 값을 저장한 스케줄의 모의 실행과 같고, 다른 점은 앞 대상에서 상한을 넘은 노드가 뒤 대상의 `exclusionCodes` 에 `COOLING_DOWN` 대신 `TEMPERATURE_EXCEEDED` 로 실리는 것 하나입니다. 지정하지 않으면 냉각 대기는 정책의 온도 상한 기준으로 주의 14 와 같이 남습니다. 스케줄·정책·워크로드·이력·전달·실행 어느 행도 쓰지 않으며(정책이 없어도 `default` 를 만들지 않음), 대상이 있으면 노드를 모으므로 버튼 단위로 호출하고, 200 이 저장 성공을 뜻하지는 않습니다(스케줄명·실행 주기와 `scheduleId` 형식·중복은 검사하지 않음). 옛 서버에는 이 경로가 없어 403 입니다.


**실행 상세의 `clusterSnapshot`**

실행 1회가 클러스터를 무엇으로 바꿨는지 보여 주는 칸입니다. 축이 둘로 갈립니다.

| 칸 | 뜻 | 전·후 |
|---|---|---|
| `knobsDigest` | 이 회차의 숫자를 정의한 설정 노브의 지문 | 한 번만 |
| `nodeCount` | 배치 후보에 오른 노드 수 | 한 번만 |
| `excludedCount` | 메트릭을 못 얻어 수집에서 빠진 노드 수(제약 제외가 아님) | 한 번만 |
| `powerMeasuredNodes` | 전력을 실측한 노드 수 | 한 번만 |
| `measuredPowerWatts` | 실측 전력 합(W) | 한 번만 |
| `activeNodesBefore` / `activeNodesAfter` | 가동으로 세는 노드 수 | 전·후 |
| `reclaimableWattsBefore` / `reclaimableWattsAfter` | 비어 있는 노드의 유휴 전력 합(W). 계측 노드는 실측 역산, 미계측 노드는 정격 추정 | 전·후 |
| `avgTemperatureBefore` / `avgTemperatureAfter` | 평균 실효 온도(℃). 후는 계측이 아니라 열관성 계수로 낸 외삽 | 전·후 |

- **"후"는 다시 잰 값이 아니라 이번 실행이 잡은 예약을 반영한 값입니다.** 배치 직후에는 설비가 아직 반응하지 않아 다시 재도 같은 숫자이기 때문입니다. 그래서 실측에서 오는 위 네 칸은 전·후를 두지 않았습니다. 모의 실행(주의 14)의 `clusterSnapshot` 도 같은 열두 칸이며 "후" 는 모의가 잡은 예약을 반영한 값입니다.
- 예약은 **배치 엔진이 노드를 고른 시점** 기준이되, 그 결정의 저장에 실패한 배치는 예약을 잡지 않고, 실행 계층이 받지 않은 배치(전달 상태 `REJECTED`·`UNAVAILABLE`)의 예약은 같은 실행 안에서 되돌립니다. 그래서 받지 않은 배치의 자원은 같은 실행의 다음 워크로드부터 다시 쓰이고, 배치가 전부 무산된 실행의 "후"는 "전"과 같습니다. 콜백으로 나중에 `FAILED` 가 된 배치는 이번 실행의 "후"에 남고 다음 실행의 예약에서 빠집니다.
- `status` 는 다섯 값입니다 — `OK`(전·후 모두), `BEFORE_ONLY`(전을 적은 뒤 후를 적기 전에 프로세스가 내려감), `NO_TARGET`(배치할 워크로드가 없어 재기 전에 끝난 평범한 회차), `NO_NODES`(노드를 한 대도 모으지 못한 장애 회차), `FAILED`(재다 오류). 배치가 예외로 실패한 회차는 `BEFORE_ONLY` 가 아니라 `OK` 입니다 — 후까지 적고 끝납니다.
- **`knobsDigest` 가 다른 구간은 한 축으로 묶어 집계하지 마십시오.** 가동 판정 문턱·유휴 비율·유휴 역산 스위치·열관성 계수는 정책이 아니라 서버 설정에서 오므로, 같은 정책 이름으로도 값이 바뀔 수 있고 바뀌면 그 뒤 회차의 숫자는 다른 정의가 됩니다.
- **이 값들은 순간 전력(W)이지 에너지(Wh)가 아닙니다.** 회차별 차를 더해도 절감량이 되지 않습니다 — 각 값이 얼마 동안 유효했는지를 이 응답이 알지 못합니다. 자동 실행은 대상이 0건이면 이력 행 자체가 없어 한가했던 구간도 빠집니다. 기간 절감량은 전력 최적화 진단의 추세 API 를 쓰십시오.
- 이 스냅샷은 실행 이력 보존 기간(기본 90일)이 지나면 실행 이력과 함께 지워집니다. 더 긴 구간이 필요하면 따로 적재해야 합니다.
- **`clusterSnapshot` 자체가 `null` 일 수 있습니다** — 이 기능 이전에 쌓인 실행 이력이 그렇습니다. 전력을 못 잰 서버에서는 `powerMeasuredNodes` 가 0 이고 전력 칸이 `null` 입니다. 0 으로 읽지 마십시오.

**실행 입력 저장**

스케줄 실행(자동·즉시)은 배치 루프에 들어가기 직전의 입력을 서버 DB 의 `schedule_run_inputs` 표에 실행 1회당 한 행으로 남깁니다. 1세부 실데이터가 들어온 뒤 그 회차를 그대로 다시 돌려 보려는 기록이며, **저장한 원문은 API 응답에 실리지 않습니다** — 실행 목록·상세 응답은 그대로이고, 이 입력으로 다시 계산한 판정은 아래 「실행 재생」이 돌려줍니다.

- 담는 것: 노드 상태(예약 반영 뒤 · 수집 순서) · 수집에서 빠진 노드 · 대상 워크로드(배치 순서) · 정책 사본(스케줄의 온도 상한 반영 뒤) · 실행 전력 상한 · MDC 예산 · 링크 원장(링크 게이트를 켠 정책만) · 냉각 대기 · 전력·열·네트워크 설정. 실행 이력의 `knobsDigest` 와 같은 지문을 함께 둡니다.
- 대상이 없거나 노드를 한 대도 모으지 못한 회차, 모의 실행(주의 14·15)은 남기지 않습니다. 저장이 실패해도 배치는 그대로 진행합니다.
- 실행 이력과 같은 기간(기본 90일)만 남고, 보존 정리와 스케줄 삭제가 실행 이력을 지울 때 함께 지워집니다.
- 단건 배치(`POST /workloads/{id}/schedule`, `dryRun=true` 가 아닐 때)도 배치 직전의 입력을 같은 형식으로 `placement_inputs` 표에 1회당 한 행 남깁니다(대상 워크로드 1건).
  - 같은 행에 엔진 결정(실제로 쓴 엔진 · 성공 여부 · 선택 노드)을 둡니다. 엔진이 낸 값이며, 실행 계층이 받지 않아 실패로 낮춘 결과는 전달 기록에 남습니다.
  - **실패한 결정도 남깁니다.** 단건 배치의 실패 결정은 배치 이력에 남지 않아 이 행에만 있습니다.
  - 노드를 한 대도 모으지 못하면 남기지 않습니다. 저장이 실패해도 응답은 그대로이고, 응답·Swagger 는 바뀌지 않으며, 이 입력을 다시 계산하는 API 는 없습니다.
  - 저장 시각 기준으로 실행 이력과 같은 기간(기본 90일)이 지나면 지워지고, 워크로드를 지워도 그때까지 남습니다.
- 재생의 한계: 배치 직전 다시 읽어 건너뛴 워크로드와 실행 계층이 받지 않은 배치는 배치 이력·전달 기록에서 함께 읽어야 같은 결정이 나옵니다. 실행 도중의 워크로드 편집, 실행 도중 만료된 냉각 대기, 같은 시각 다른 배치가 건 냉각 대기, 엔진 코드 상수의 변경(코드 리비전은 남기지 않음)은 재현하지 못합니다.

**실행 재생**

`GET /{id}/runs/{runId}/replay` 는 위 입력으로 그 실행의 배치를 **지금 서버 코드로 다시 계산**해 워크로드별로 배치 이력과 대조합니다. 화면은 이 경로를 부르지 않습니다 — 1세부 실데이터로 돌린 실행을 다시 계산해 보거나, 빌드가 바뀐 뒤 같은 입력에서 엔진 결정이 달라졌는지 확인하는 검증용입니다. DB 에 아무것도 저장하지 않고, 운영 냉각 대기도 건드리지 않습니다(엔진·모델을 요청마다 새로 만듦).

- 응답 키 14개 순서 고정 — `runId`·`formatVersion`·`capturedAt`·`engineId`·`inputDigest`·`knobsDigest`·`verdict`·`total`·`matched`·`mismatched`·`skipped`·`notRecorded`·`missingDispatchRecords`·`steps`. `steps[]` 는 배치 순서 그대로이고 한 줄은 8키 — `workloadId`·`outcome`·`recordedNodeId`·`recordedScore`·`recordedSuccess`·`replayedNodeId`·`replayedScore`·`replayedSuccess`. 저장한 입력 원문(payload)은 싣지 않습니다.
- 대조는 워크로드 id 로 하고, 선택 노드·노드 배치 스코어(`finalScore`, 오차 없이)·엔진 결정 성공 여부가 모두 같아야 `MATCH` 입니다. `recordedSuccess` 는 배치 이력의 엔진 결정이라 실행 계층이 받지 않은 배치도 `true` 입니다.
- `outcome` 은 넷 — `MATCH`·`MISMATCH`·`SKIPPED`(실행이 배치 직전 다시 읽어 건너뛴 워크로드 — 다시 계산하지 않아 `replayed*` 가 `null`)·`NOT_RECORDED`(배치 이력 행이 없음 — 실행이 도중에 끊긴 경우 등, `recorded*` 가 `null`).
- `verdict` 는 셋 — `MATCH`(확인할 수 없는 구간 없이 건너뛰지 않은 워크로드가 모두 같음)·`MISMATCH`(확인할 수 있는 구간에서 어긋난 워크로드가 있음)·`INCOMPLETE`(확인할 수 있는 구간은 모두 같지만 확인할 수 없는 구간이 있음 — 그 구간이 모두 같아도 `INCOMPLETE`). 확인할 수 없는 구간은 이력 행이 없는 워크로드부터, 그리고 배치 이력은 성공인데 이 실행의 전달 기록이 없는 워크로드(`missingDispatchRecords` — 워크로드를 지워 전달 기록이 함께 지워졌거나 같은 결정의 기록이 다른 실행에 있던 경우)의 다음 워크로드부터입니다 — 그 배치의 예약을 실행이 되돌렸는지 알 수 없기 때문입니다.
- 확인할 수 없는 구간의 단계도 `outcome` 은 `MATCH`·`MISMATCH` 로 나오지만 판정에는 넣지 않습니다. 재생은 다시 계산한 결정으로 예약을 잡으므로 첫 `MISMATCH` 뒤 단계는 그 차이 때문에 함께 어긋날 수 있습니다 — 원인은 첫 `MISMATCH` 단계부터 봅니다. 실행 뒤 그 실행이 배치한 워크로드를 지우면 전달 기록이 함께 지워져 그 다음 워크로드부터 확인할 수 없습니다.
- 실행 계층이 받지 않은 배치(전달 상태 `REJECTED`·`UNAVAILABLE`)는 실행과 같이 예약을 되돌리고, 건너뛴 워크로드는 건너뜁니다 — 둘 다 입력이 아니라 배치 이력·전달 기록에서 읽습니다.
- **`MISMATCH` 가 곧 결함은 아닙니다.** 위 「재생의 한계」(실행 도중의 워크로드 편집 · 실행 도중 만료된 냉각 대기 · 같은 시각 다른 배치가 건 냉각 대기)는 재생하지 못하고, 엔진 코드가 바뀐 빌드에서는 어긋남이 의도한 신호일 수 있습니다. 노드 수집 단계(`createContext`)의 변경은 저장한 입력이 수집 뒤의 값이라 이 API 로 드러나지 않습니다.
- 오류 — 404: 스케줄·실행이 없음 · 다른 스케줄의 실행 · 저장한 입력이 없음(입력 저장 전 실행 · 대상이나 노드가 없어 배치 전에 끝난 실행 · 저장 실패). 409: 아직 실행 중(`RUNNING`) · 지금 읽을 수 없는 형식 판(1이 아님) · 원문이 저장한 지문(`inputDigest`)과 다름 · 원문을 지금 코드로 읽을 수 없음 · 다시 만들 수 없는 엔진. 500: 다시 계산하다 예외가 남(엔진 계산 중이면 사유에 워크로드 id). 응답 크기는 대상 수에 비례하며 실행 1회 상한(기본 500건)이면 100KB 안팎입니다(추정). 옛 서버에는 이 경로가 없어 403 입니다.

**엔진별 재계산**

`GET /{id}/runs/{runId}/replay/engines` 는 위 실행 재생과 같은 입력으로 운영 엔진 세 개(`weighted`·`power`·`temperature`)를 **각각 새로 조립해** 다시 계산하고, 워크로드별 선택 노드와 성공 여부를 배치 이력과 나란히 돌려줍니다. 같은 정책에서 스케줄 엔진만 바꿨다면 어떻게 배치됐을지를 같은 실제 입력에서 보는 검증·연구용이며, 화면은 부르지 않고 DB 에 아무것도 저장하지 않습니다.

- 응답 키 12개 순서 고정 — `runId`·`formatVersion`·`capturedAt`·`recordedEngineId`·`inputDigest`·`knobsDigest`·`replayVerdict`·`total`·`skipped`·`rolledBack`·`engines`·`steps`. `engines[]` 는 5키(`engineId`·`recorded`·`placed`·`failed`·`sameAsRecorded`) · 엔진 순서는 `weighted`·`power`·`temperature` 로 고정. `steps[]` 는 배치 순서 그대로 5키(`workloadId`·`skipped`·`recordedNodeId`·`recordedSuccess`·`choices`) · `choices[]` 는 3키(`engineId`·`nodeId`·`success`)로 `engines` 와 같은 순서.
- `replayVerdict` 는 실제로 쓴 엔진을 다시 계산한 실행 재생 판정입니다(위 「실행 재생」과 같은 규칙 · 실제로 쓴 엔진은 한 번만 계산해 판정과 그 엔진 열을 같은 결과에서 냄). `MATCH` 가 아니면 다른 엔진 열을 같은 조건의 대안으로 읽을 수 없습니다.
- `sameAsRecorded` 는 선택 노드·성공 여부가 배치 이력과 같은 워크로드 수입니다. 건너뛴 워크로드와 이력 행이 없는 워크로드는 세지 않고, 실패와 실패는 같은 것으로 셉니다. 노드 배치 스코어만 다른 경우는 `replayVerdict` 에만 드러납니다.
- **노드 배치 스코어·평가 지표·순위·실행 전후 클러스터 상태는 싣지 않습니다.** 스코어는 엔진마다 뜻이 다르고, 회수 가능 전력·외삽 온도 같은 값은 엔진 자신의 모델로 낸 값이라 엔진끼리 나란히 두면 판정처럼 읽힙니다(docs/14 2.1절·7절). **이 응답으로 엔진 우열을 말하지 않습니다** — 엔진 우열 판정은 8장 배치 전략 비교의 판정판에서만 합니다.
- 가정: 건너뛴 워크로드는 모든 엔진에서 건너뜁니다(배치 직전 다시 읽은 워크로드 상태로 정해져 엔진과 무관). 실행 계층이 받지 않아 예약을 되돌린 워크로드(`rolledBack`)는 모든 엔진에서 예약을 되돌리고, 그 밖의 배치는 엔진이 고른 노드가 기록과 달라도 실행 계층이 받았다고 보고 예약을 유지합니다. 지금 서버는 전달을 모두 받는 기록 모드라 되돌림이 0건이지만, 실행 계층이 노드마다 다르게 받게 되면 `rolledBack` 이 0 이어도 다른 엔진 열은 실제와 다를 수 있습니다. 실행 엔진은 스케줄의 `engine`, 비었으면 정책 기본 엔진(등록된 것일 때), 그것도 없으면 서버 기본 엔진 순으로 정해지므로, 다른 엔진 열은 「스케줄에 그 엔진을 지정했다면」의 결과입니다.
- 404·409 는 실행 재생과 같은 검사를 거칩니다. 500 은 세 엔진 중 하나라도 다시 계산하다 예외를 내면 나며(사유에 엔진 id), 실행 재생이 200 인 실행에서도 날 수 있습니다. 응답 크기는 대상 수에 비례하며 실행 1회 상한(기본 500건)이면 150KB 안팎입니다(추정). 옛 서버에는 이 경로가 없어 403 입니다.

---

## 3. 워크로드 · 노드 · 이력 — `/api/v1/scheduler` (26)

진단 화면과 스케줄 화면이 함께 씁니다.

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET / POST | `/workloads` | 목록(`status` 필터 · 최신순 배열 · **전량** — 표 화면은 `/workloads/page`) / 생성 |
| GET | `/workloads/page` | 목록 페이징. `status`, `priority`, `resourceType`, `keyword`, `assignedNodeId`, `retryExhausted` 필터(아래 주의 12) |
| GET | `/workloads/retry-policy` | 재시도 정책 — 상한 `maxRetryCount`·간격 `retryBackoffMinutes`(아래 주의 6·7) |
| GET / PUT / DELETE | `/workloads/{id}` | 상세 / 편집(부분 갱신 · 아래 주의 14) / 삭제 |
| GET | `/workloads/resource-types` | 리소스 종류 목록 `[{code, label}]`(1.4절) |
| PATCH | `/workloads/{id}/status` | 상태 변경 |
| POST | `/workloads/{id}/release` | 배치 예약 취소 — SCHEDULED 를 대기로 되돌리고 배정을 풀며 전달 기록을 마감(아래 주의 13) |
| POST | `/workloads/resume-exhausted` | 재시도 소진 워크로드 일괄 재개. `resourceType`, `from`, `to` |
| POST | `/workloads/{id}/schedule` | 단건 배치. `engine`, `dryRun`. 응답에 적용 정책 `policyName`(주의 3) · 배치 직전 입력과 엔진 결정을 남김(2장 「실행 입력 저장」) |
| POST | `/workloads/dry-run` | 저장 전 모의 배치 — 생성 폼 값으로 단건 모의 배치와 같은 일곱 키(저장 없음 · 주의 15) |
| GET | `/nodes`, `/nodes/{nodeId}` | 노드 목록·상세(27필드 — 아래 GPU 축·디스크 축) |
| GET | `/nodes/excluded` | 수집 실패로 후보에서 빠진 노드와 사유 |
| GET | `/nodes/power-efficiency` | 노드별 전력 효율 지표 |
| GET | `/nodes/{nodeId}/power-efficiency/trend` | 효율 추세. `from`, `to`, `step` |
| GET | `/constraints` | 제약 조건 설명(C0~C6) |
| GET | `/thermal/cooldowns` | 냉각 대기 중인 노드 `[{mdcId, nodeId, temperatureCelsius, thermalState, remainingCooldownMinutes}]`(1.4절 · 아래 주의 16) |
| GET | `/engines` | 선택 가능한 엔진 `[{id, name}]` |
| GET | `/history` | 배치 이력. 최신순 배열, **최근 1000건까지** |
| GET | `/history/page` | 배치 이력 페이징. 상한 없음. `workloadId`, `success`, `engineName`, `scheduleRunId`, `exclusionCode`, `from`, `to` 필터(아래 주의 5) |
| GET | `/history/exclusion-summary` | 배치 이력의 제외 사유 코드 합계. `workloadId`, `success`, `engineName`, `scheduleRunId`, `from`, `to` 필터(아래 **`exclusionCodes`(배치 이력 · 실행 상세 결정)** 의 **합계** 행) |
| GET | `/workloads/{id}/history` | 워크로드별 배치 이력 |
| GET | `/metrics-source` | 지금 보고 있는 메트릭 출처 |

**화면을 붙일 때 주의할 것**

1. **상태 변경 API 로 배치 상태(SCHEDULED)로 바꿀 수 없습니다.** 배치는 엔진이 정합니다. 실패한 워크로드는 대기로만 되살릴 수 있고, 되살리면 재시도 횟수가 0 으로 지워집니다. 배치된(SCHEDULED) 워크로드를 대기로 되돌리는 것도 상태 변경 API 로는 400 이며 **예약 취소 API**(주의 13)로만 합니다.
2. **전력·온도를 수집하지 못한 노드는 값이 0 으로 옵니다.** `powerSource` 가 `NONE` 이면 센서가 없는 노드이니 화면에서 "-" 로 처리하십시오. 현재 배포 서버가 이 상태입니다.
   GPU 축 세 칸도 같은 규약입니다 — 아래 **노드 응답의 GPU 축** 참고.
3. **단건 배치에 `dryRun=true`** 를 주면 저장 없이 결과만 돌려줍니다. 모의 배치 화면에 그대로 쓸 수 있습니다. 응답은 `dryRun` 여부와 관계없이 `success`·`selectedMdcId`·`selectedNodeId`·`finalScore`·`reason`·`candidateScores`·`policyName` 일곱 키입니다. **`policyName`** 은 이 결정에 적용된 배치 정책 이름으로 비어 있지 않습니다 — 5장 선택 순서 ②~④(단건 배치에는 스케줄 지정 정책이 없음)로 고르며, 성공·실패·모의 배치·실행 계층 거부 결과 모두에 있고 활성 노드가 없어 후보 없이 실패한 경우에도 그 시점의 정책입니다. 종류를 비운 워크로드는 VM 으로 읽어 ②에서 VM 활성 정책을 찾습니다(스케줄 실행·워크로드 페이징과 같은 규칙 · 워크로드의 저장값·조회 응답 `resourceType` 은 원값 `null` 그대로). 이름은 유일하므로 `GET /policies` 의 `name` 으로 찾으면 되며 ④의 `default` 는 비활성일 수 있습니다. 단건 배치의 이력은 성공한 결정만 남기므로(스케줄 실행은 실패 결정도 정책명과 함께 남김) 단건 배치의 실패·모의 배치에 쓰인 정책은 이 응답으로만 알 수 있습니다. 저장 전 폼의 모의 배치는 주의 15 입니다.
4. 배치 이력에는 최종 스코어(`finalScore`)와 항목별 노드 배치 스코어 다섯 필드(이름은 CPU·메모리·전력·온도·네트워크이지만 엔진마다 담는 값이 다릅니다 — 아래 **엔진별 노드 배치 스코어의 뜻**), 선택 당시 노드 상태, 후보 수, 사유, 엔진 id(`engineName`), 정책명, 그리고 후보 노드의 **제외 사유 코드 집계(`exclusionCodes`)**가 함께 있습니다(23필드). `exclusionCodes` 를 읽는 법은 아래 **`exclusionCodes`(배치 이력 · 실행 상세 결정)** 을 보십시오.
5. `/history` 는 배열이고 상한이 있습니다. 전체가 필요하면 `/history/page` 를 쓰십시오. 필터 일곱 가지는 전부 선택입니다 — `workloadId` · `success`(`true` 는 성공 여부가 비어 있는 옛 행도 포함, `false` 는 명시적 실패만) · `engineName` · `scheduleRunId` · `exclusionCode`(그 코드로 센 후보가 하나 이상 있는 결정만 — 아래 **`exclusionCodes`(배치 이력 · 실행 상세 결정)** 의 **필터** 행) · `from`·`to`(결정 시각 창 — 다음 줄). 실행 단위로 모아 보는 법은 아래 **실행 필터(`/history/page?scheduleRunId=`)** 를 보십시오.
   `from`·`to` 는 결정 시각 `createdAt` 의 창으로 `[from, to)` 반개구간입니다. 값은 오프셋까지 든 ISO-8601 이고(예 `2026-09-21T00:00:00+09:00`, 쿼리에서는 `+` 를 `%2B` 로), 오프셋이 없거나 형식이 틀리거나 시작이 끝보다 뒤면 400 입니다. 비운 쪽은 경계가 없고, 합계와 달리 **기본 창이 없습니다**. 비교는 순간(instant) 기준입니다.
6. **재시도에 간격이 있습니다.** 배치에 실패한 워크로드는 곧바로 다시 시도하지 않고 마지막 실패 `lastFailedAt` 에서 `retryBackoffMinutes`(설정값 · 기본 15분 · `GET /workloads/retry-policy` 로 확인, 0 이하면 간격 없음)이 지난 뒤부터 대상이 됩니다. 실행 결과가 "대상 없음 (재시도 대기 N건 — M분 뒤 다시 시도합니다)" 이면 정상이며 기다리면 됩니다(M 은 `retryBackoffMinutes` 설정값 그대로이고 남은 시간이 아닙니다). 화면에서 "대기" 를 표시하려면 `status = FAILED` 이고 소진이 아니며(`retryCount < maxRetryCount`, 주의 7) `lastFailedAt + retryBackoffMinutes` 가 아직 지나지 않은 행입니다(`lastFailedAt` 이 비면 대기 아님) — 시각에 따라 바뀌는 값이라 워크로드 응답 필드로 주지 않습니다(스케줄 기준 건수는 2장 주의 11 의 미리보기 `waitingCount`). 이 계산은 브라우저 시계 기준이라 서버와 몇 분 어긋날 수 있고, 간격이 지나도 실제 재시도는 다음 스케줄 실행 때입니다 — "대기 끝" 을 "재시도 시각" 으로 표시하지 마십시오.
7. **"대상 없음 (재시도 소진 N건 — 일괄 재개가 필요합니다)" 은 실행이 실패로 끝납니다.** 사람이 되살리기 전에는 그 스케줄이 아무 일도 못 하기 때문입니다(소진과 대기가 함께 있으면 소진 메시지만 나옵니다). 되살리는 길은 상태 전이(`FAILED` → `PENDING`, 단건, 주의 1)와 일괄 재개 API 둘입니다. **소진의 판정식은 `status = FAILED` 이고 `retryCount ≥ maxRetryCount`**(설정값 · 기본 5 · `GET /workloads/retry-policy`)이며, 목록에서 소진 행만 보려면 `/workloads/page?retryExhausted=true` 를 쓰십시오(일괄 재개와 같은 식 — `resourceType` 을 함께 걸면 같은 종류로 부른 일괄 재개의 대상 건수와 같고, 기간은 페이징에 없어 기간으로 재개하면 건수가 다를 수 있습니다). 스케줄 하나를 기준으로 한 건수는 2장 주의 11 의 미리보기 `exhaustedCount` 입니다. 단건 배치(`/workloads/{id}/schedule`)는 소진·간격을 보지 않으므로 소진된 워크로드도 단건으로는 배치되고, 엔진·실행 계층 실패면 `retryCount` 가 계속 오르며(활성 노드 0 은 제외) 성공해도 지워지지 않아 이후 `FAILED` 가 되면 곧바로 다시 소진입니다 — 소진은 "스케줄 실행 대상에서 빠졌다" 는 뜻입니다.
8. **일괄 재개는 리소스 종류나 기간 중 하나가 필수**이고 한 번에 설정 상한(`aims.scheduler.max-workloads-per-run`, 기본 500 — 스케줄 실행 1회 처리 상한과 같은 설정)까지이며 넘으면 되살리지 않고 400 입니다. 조건 없이 부르면 400 입니다. 응답은 `resumedCount` 와 되살린 워크로드 목록입니다.
9. **워크로드 생성에 `requiredBandwidthMbps`(Mbps, 유한한 0 초과)를 선택으로 줄 수 있습니다.** 값이 반영되려면 **세 가지가 모두** 맞아야 합니다 — ① 정책의 데이터 MDC 친화도가 켜져 있고, ② 그 워크로드에 `dataMdcId` 가 있고, ③ 배치 엔진이 `weighted` 여야 합니다. 링크 점수를 총점에 섞는 엔진은 `weighted` 하나뿐이라 `power`·`temperature` 로 배치하면 요구 대역폭은 **전혀 반영되지 않습니다**. 셋이 맞으면 MDC 링크 원장의 대역폭이 이 값에 모자란 비율만큼 링크 점수를 깎습니다(제약이 아니라 점수라 부족해도 배치는 됩니다). 주지 않으면 `null` 이고 예전과 똑같이 계산됩니다. 원장에 대역폭이 없는 링크와 같은 MDC·미등록 원격 쌍은 견줄 값이 없어 영향이 없습니다 — 그래서 **요구가 링크의 몇 배로 크면 대역폭을 적어 둔 링크가 안 적은 링크보다 낮은 점수를 받을 수 있습니다**(20ms 링크 기준 요구가 2.5배를 넘을 때). 값을 지우려면 편집 API 에 `"requiredBandwidthMbps": null` 을 보냅니다(`PENDING`·`FAILED` 에서만 · 주의 14).
10. **엔진 목록의 `name` 은 자바 클래스명**(`PowerAwareScheduler` 등)입니다. 화면에 그대로 노출하기 어려우니 `id`(`weighted`·`power`·`temperature`)로 판단하고 표시 문구는 프론트에서 정하십시오. 표시명 추가는 후속 과제로 두었습니다.
11. **디스크를 요구한 배치(`requiredDiskGb > 0`)의 후보 상세에는 `details.디스크검사` 가 붙습니다.** `1.0` 은 노드의 파일시스템 총량을 알아 C1 디스크 검사의 대상인 노드, `0.0` 은 총량을 몰라 디스크 요구를 보지 않고 통과시킨 노드입니다 — **적격·부적격과는 무관**하고(CPU 부족으로 걸린 노드도 총량을 알면 `1.0`), 부족은 `exclusionCode = INSUFFICIENT_DISK` 로 옵니다. 키의 유무는 워크로드가, 값은 노드가 정하므로 한 응답 안의 후보들은 같은 키 집합을 갖습니다 — 키를 표의 열로 쓴다면 응답마다 열을 다시 잡으십시오. 키가 없는 것은 "검사 안 함" 이 아니라 디스크 요구가 없는 배치입니다. `0.0` 노드가 적격에 있으면 서버가 배치 1회당 WARN 한 줄(마운트 설정·노드 예시)을 남깁니다. 노드 응답의 `diskUsagePercent` 는 총량을 모르는 노드에서도 0 이라 "여유" 로 읽으면 안 됩니다 — 총량을 아는지는 이 키 또는 노드 응답의 `diskCapacityGb`(0 = 모름, 아래 디스크 축)로 알 수 있습니다.
12. **워크로드 표는 `GET /workloads/page` 를 쓰십시오.** 기존 `/workloads` 는 배열로 상한 없이 전량을 주고 워크로드는 자동 정리가 없어 계속 늘어납니다. 페이징 응답은 1장 「목록」의 `{ items, page, size, totalElements, totalPages }`(행은 `/workloads` 와 같은 23필드) · `page` 1부터 · `size` 기본 20 최대 100 · 기본 정렬 `createdAt,desc` 이고 허용 필드와 보조 정렬은 1.1절에 있습니다.
   **필터 여섯**은 전부 선택이고 AND 로 겹치며 총건수(`totalElements`)는 필터를 반영합니다. `status`(다섯 값 · 대소문자 무시 · 다른 값은 400 에 가능한 값 목록 · **여러 개면 반복 `status=SCHEDULED&status=RUNNING` 이나 쉼표로, 서로 OR**) · `priority`(`LOW`·`NORMAL`·`HIGH`·`CRITICAL` 네 값 — 이 순서가 낮음 → 높음이고 선택지 목록 API 는 없으니 이 네 값을 고정 선택지로 쓰십시오. 생성 요청의 `priority` 도 같은 네 값이며 생략하면 `NORMAL` 입니다 · 대소문자 무시 · 다른 값은 400 에 가능한 값 목록 · `status` 와 같이 **반복 `priority=HIGH&priority=CRITICAL` 이나 쉼표로 여러 값, 서로 OR** · 생략한 행이 `NORMAL` 이라 비어 있는 행이 없어 네 값의 건수를 더하면 전체입니다 · `sort=priority` 는 등급 순(1.1절)) · `resourceType`(`VM`·`GPU`·`NPU`·`CONTAINER` · 대소문자 무시 · 한 번에 한 값 · **`VM` 은 종류를 비워 둔 행(생성 때 생략한 행·옛 행)도 포함**합니다 — 실행이 비어 있는 종류를 VM 으로 해석하는 것과 같은 규칙이고, 응답의 `resourceType` 은 원값(`null`) 그대로이니 표에는 VM 으로 표기해도 됩니다) · `keyword`(이름·설명만의 부분 일치 · id·노드는 찾지 않음 · 앞뒤 공백 제거 · 대소문자 무시(영문 기준) · `%` 와 `_` 는 문자 그대로 찾음) · `assignedNodeId`(`/nodes` 의 `nodeId` 와 같은 값 · 앞뒤 공백을 뺀 뒤 정확히 일치 · **마지막으로 배정된 노드**라 완료·실패·대기 복귀(`FAILED → PENDING`) 뒤에도 남고, 실행 계층이 거부해 실패한 행과 예약을 취소한 행(주의 13)만 비워집니다 — 그 노드에 지금 올라가 있는 것만 보려면 `status=SCHEDULED&status=RUNNING` 을 함께 거십시오) · `retryExhausted`(`true` 면 재시도 소진 행만 — `status = FAILED` 이고 `retryCount ≥ maxRetryCount`(주의 7) · `false` 면 그 나머지 · 값은 `true`·`false` 로 보내십시오(해석할 수 없는 값은 400) · 다른 필터와 AND 라 `true`&`status=PENDING` 은 늘 0, `false`&`status=FAILED` 는 아직 재시도할 수 있는 실패). 빈 값은 필터 없음입니다.
   **동률과 조회 사이의 변경.** 같은 값으로 정렬된 행은 `id` 로 한 번 더 정렬되어 페이지를 이어 받아도 겹치거나 빠지지 않습니다 — 조회 사이에 데이터가 바뀌지 않을 때의 보장입니다. 조회 사이에 워크로드가 생기거나 지워지거나, 편집이 이름·설명·우선순위·종류·요구량을 바꾸거나(주의 14), 스케줄 실행이 상태·배치 시각·재시도 값을 바꾸면(그 필드로 필터·정렬한 경우) 행이 한 칸씩 밀리거나 당겨져 겹치거나 빠질 수 있습니다(오프셋 페이징 공통).
   **삭제와 목록.** `DELETE /workloads/{id}` 는 `SCHEDULED`·`RUNNING` 이면 409 이므로 두 상태에서는 삭제 버튼을 막으십시오. 삭제는 곧바로 반영돼 다음 조회의 `totalElements` 가 줄어듭니다. `page` 가 `totalPages` 를 넘으면 오류가 아니라 빈 `items` 이니 끝 페이지의 마지막 행을 지웠으면 한 페이지 앞을 부르십시오. 배치 이력은 함께 지워지지 않습니다.
13. **배치 예약 취소 `POST /workloads/{id}/release`** 는 배치된(`SCHEDULED`) 워크로드를 **대기(`PENDING`)로 되돌리고 배정(`assignedMdcId`·`assignedNodeId`·`schedulingScore`)을 풉니다.** 응답은 네 키 — `workload`(23필드 · 상태 `PENDING` · `schedulingReason` 은 "예약 취소 — 이전 배정 {mdc}/{node}") · `releasedMdcId` · `releasedNodeId` · `dispatch`(7장의 전달 기록 13필드 · `ACCEPTED` 였으면 `FAILED` 로 마감한 뒤의 값 · 이미 끝 상태였으면 그대로 · 현재 결정의 기록이 없으면 — 전달 기능 이전의 배정 — `null`). **취소는 실패가 아닙니다** — `retryCount`·`lastFailedAt` 은 바뀌지 않고, 추적용 `scheduledAt` 은 남으며, 배치 이력도 그대로입니다(취소 행을 새로 쓰지 않음). 결과는 **재대기**입니다 — 다음 스케줄 실행이 다시 대상으로 잡고, 자동 실행이 켜진 스케줄이 있으면 곧 재배치될 수 있습니다 — 풀린 노드를 피하지 않으므로 같은 노드로 다시 갈 수 있습니다. **재시도 상한에 걸린 워크로드를 단건 배치로 올린 뒤 취소하면** 재시도 횟수가 그대로라 스케줄 실행 대상이 아니고(소진 목록·일괄 재개는 `FAILED` 만 보므로 거기에도 없음) 단건 배치로만 다시 배치됩니다. 백오프 중이던 워크로드는 간격이 지난 뒤 대상이 됩니다. 노드 예약은 워크로드 상태로 매번 다시 계산하므로 취소 직후 `GET /nodes` 의 `reserved*` 에서 빠집니다. 삭제는 `SCHEDULED` 에서 409 이므로 **삭제하려면 먼저 이 API 로 풉니다.** 오류: 없는 워크로드 404 · `SCHEDULED` 가 아니면 409(`RUNNING` 은 실행 계층 결과로 끝내야 함 · `PENDING`·`FAILED`·`COMPLETED` 는 취소할 예약이 없음) · 현재 결정의 전달 기록이 전달 중(`REQUESTED`)·실행 중(`RUNNING`)이면 409. 409 는 이렇게 두 원인이라 화면은 응답 `message` 대신 워크로드를 다시 조회해 상태로 분기하십시오(전달 중은 잠시 뒤 재시도 가능 · 단 단건 배치의 전달 도중 프로세스가 내려가 `REQUESTED` 가 남은 기록은 풀리지 않으므로 그때는 상태 변경 `FAILED` 뒤 대기 복귀로 정리 · 스케줄 실행이 배치한 것은 재시작 정리가 닫음 — 7장). 상태 변경 API(`PATCH /status`)로 `SCHEDULED → FAILED → PENDING` 을 흉내 내면 배정 컬럼이 남고 재시도 횟수가 지워지니 그 조합은 쓰지 마십시오(그때만 쓰십시오 · 남은 기록도 `FAILED` 로 마감됩니다 — 7장). 옛 서버에는 이 경로가 없어 403 입니다.
14. **워크로드 편집 `PUT /workloads/{id}` 는 부분 갱신입니다** — 보낸 키만 바꾸고 없는 키는 유지합니다. 응답은 상세와 같은 23필드이고, 옛 서버에는 PUT 매핑이 없어 403 입니다.
   **칸 규칙.** 칸은 생성과 같은 열세 개이고 검증·사유 문구도 생성과 같습니다. `description`·`dataMdcId`·`preferredMdcId`·`maxPowerBudgetWatts`·`maxTemperatureCelsius`·`requiredBandwidthMbps` 여섯 칸은 **`null` 을 보내면 비웁니다**. Swagger 스키마에는 null 허용이 표시되지 않으니 이 여섯 칸의 타입은 `string | null`·`number | null` 로 잡으십시오. 이 여섯 칸은 JSON 타입도 검사해 문자열 칸에 숫자, 숫자 칸에 `"100"` 같은 숫자 문자열을 보내면 400 입니다(생성은 받아 줌). 빈 문자열은 칸마다 다릅니다 — MDC 두 칸은 앞뒤 공백을 지워 비면 비움이고, `description` 은 빈 문자열도 그대로 저장하므로 비우려면 `null` 을 보내십시오. 나머지 일곱 칸(`name`·`requiredCpu`·`requiredMemoryMb`·`requiredGpu`·`requiredDiskGb`·`resourceType`·`priority`)의 `null` 은 유지입니다. `name`·`resourceType`·`priority` 의 빈 문자열은 400 이고, GPU·디스크 요구를 없애려면 0 을 보내십시오. 상태·배정·점수·사유·재시도·시각 키는 무시하므로 **상세 조회 응답의 `data` 를 그대로 보내면 아무것도 바뀌지 않습니다**. 빈 본문 `{}` 는 200 무변경, 본문이 없으면 400 입니다.
   **편집 가능 상태와 오류 순서.** 편집은 `PENDING`·`FAILED` 에서만 됩니다 — `SCHEDULED` 는 409(먼저 예약 취소, 주의 13), `RUNNING` 은 409, `COMPLETED` 는 409(실행 기록이라 새로 생성)이므로 편집 버튼은 두 상태에서만 여십시오. 값 오류는 404 → 409 → 400 순서로 보고, 한 칸이라도 400 이면 다른 칸도 반영되지 않습니다. 깨진 JSON 이나 정수 칸의 형식 오류처럼 본문을 읽지 못한 400 은 워크로드를 찾기 전에 나므로 없는 id·배치된 워크로드에도 400 입니다.
   **바뀌지 않는 것.** 재시도 횟수·마지막 실패 시각·사유·배정·`createdAt` 은 그대로입니다. 스케줄 실행 순번은 우선순위가 먼저이고(크기 순 스케줄은 요구 CPU·메모리가 다음) 같은 값 안에서는 등록 순서이므로, 이 칸들을 바꾸면 순번도 바뀝니다. 요구량을 줄여도 소진은 소진이니 편집 뒤 상태 변경 `FAILED → PENDING`(주의 1) 또는 일괄 재개(주의 7·8)로 되살리십시오. 백오프 중인 실패는 간격이 지난 뒤 대상이 됩니다.
   **기록과 이름.** 배치 이력·전달 기록은 결정 당시 이름만 따로 저장하므로 편집 전 기록은 옛 이름으로 남습니다. 요구량은 저장하지 않으므로 편집 전 결정을 지금 요구량과 나란히 보이면 그 결정이 실제로 본 값과 다를 수 있습니다. 이름 중복은 생성처럼 막지 않고, 편집 시각·편집자는 남지 않습니다.
   **배치와 겹칠 때.** 편집에는 잠금이 없습니다. 단건 배치가 노드 상태를 모으는 동안(보통 수 초) 편집하면, 배치 결과가 편집 전 값으로 저장되어 200 을 받은 편집이 사라질 수 있습니다. 스케줄 실행은 워크로드마다 배치 직전에 다시 읽으므로 대상을 조회한 뒤의 편집·되살리기는 그 배치에 반영되고, 그 사이 지웠거나 `PENDING`·`FAILED` 가 아닌 상태로 바뀐 워크로드는 그 회차에서 건너뜁니다(실패로 세고, 실행 상세 결정 목록과 배치 이력에 사유 "배치 건너뜀 — …" · `candidateCount` `0` · `exclusionCodes` `null` 인 실패 결정 한 건을 남기며, 워크로드는 바꾸지 않음). 다시 읽은 뒤 저장까지(ms) 겹친 편집·되살리기·단건 배치 결과는 여전히 덮일 수 있고, 그 사이 지우면 전처럼 회차가 "실행 중 오류: …" 로 끝납니다. 대상과 순번은 대상 조회 때 정해지므로, 그 사이 리소스 종류나 우선순위를 바꾼 워크로드도 그 회차에서 원래 순번대로 배치를 시도합니다(바꾼 값은 남습니다). 전에는 실행 시작 때 읽은 값으로 저장해 그 사이 편집·되살리기가 지워졌고, 지운 워크로드는 회차를 "실행 중 오류: …" 로 끝내 남은 대상이 배치되지 않았습니다. 드물게 편집과 배치 완료가 같은 순간에 겹치면 막 끝난 배치 결과가 지워지고 워크로드가 편집 전 상태(`PENDING`·`FAILED`)로, 재시도 횟수·마지막 실패 시각도 편집 전 값으로 돌아갈 수 있습니다(배치 이력·전달 기록은 남고, 전달 기록이 `ACCEPTED` 면 실행 계층은 요청을 이미 받은 상태입니다). 실제로 바뀐 칸이 없는 편집(상세 응답을 그대로 보낸 경우 등)은 저장하지 않으므로 덮어쓰지 않습니다. 자동 실행 스케줄이나 단건 배치와 함께 쓰는 화면이라면 편집 뒤 다시 조회해 상태와 값을 확인하십시오.
15. **저장 전 모의 배치 `POST /workloads/dry-run`** 은 생성 폼 값으로 저장 없이 단건 모의 배치(`dryRun=true`)와 같은 일곱 키를 돌려줍니다. 본문은 생성과 같은 열세 칸이고 400 규칙·사유 문구도 생성과 같습니다(편집의 `null` 비움 규칙은 해당 없음). 종류를 비우면 VM 으로 읽어 정책을 고릅니다(주의 3). 오류 순서는 본문 읽기 400 → 칸 400 → 엔진 400 이고 404 는 없습니다. 워크로드·배치 이력·정책을 만들지 않으며 — 저장된 워크로드의 모의 배치와 달리 정책이 하나도 없어도 `default` 를 저장하지 않습니다(5장 ④) — 응답은 호출 시점 노드 상태의 결과라 200 이 이후 저장·배치 성공을 보장하지 않습니다. 호출마다 노드 메트릭을 수집하므로(최대 수십 초) 키 입력마다가 아니라 버튼 단위로 부릅니다. 옛 서버에는 매핑이 없어 403 입니다.
16. **냉각 대기는 그 대기를 건 온도 상한 이하의 상한으로 평가할 때만 노드를 뺍니다.** 온도 인지를 켠 정책(기본값)으로 평가할 때 노드의 현재 온도가 그 평가의 온도 상한(정책의 `maxTemperatureCelsius` · 스케줄이 온도 상한을 지정하면 그 값)을 넘으면, 그 노드는 `TEMPERATURE_EXCEEDED` 로 빠지고 그 상한으로 냉각 대기가 걸립니다(`aims.scheduler.thermal.cooldown-minutes` · 기본 10분). 그동안 상한이 그 값 이하인 평가는 그 노드를 `COOLING_DOWN`(「냉각 대기 중 (N분 남음)」 · N 은 그 평가를 막는 냉각 대기 가운데 가장 늦게 풀리는 것 기준)으로 빼고, 상한이 더 높은 평가는 막히지 않고 현재 온도와 나머지 제약으로 판정합니다. 단건 배치·모의 배치(주의 3)·저장 전 모의 배치(주의 15)·스케줄 실행·모의 실행(2장 주의 14)·온도 상한을 지정하지 않은 저장 전 모의 실행(2장 주의 15)이 같은 냉각 대기를 보고 겁니다. 통합 권고(4장 주의 5)와 온도 상한을 지정한 저장 전 모의 실행은 같은 냉각 대기를 보고 노드를 빼지만, 새로 걸지는 않습니다. `GET /thermal/cooldowns` 는 어느 상한으로든 냉각 대기가 걸린 노드를 노드마다 한 행으로 내고, `remainingCooldownMinutes` 는 그 노드의 냉각 대기가 모두 풀릴 때까지의 시간입니다 — 목록에 있다고 모든 정책이 그 노드를 막는 것은 아닙니다. `thermalState` 는 냉각 대기와 별개로 현재 온도를 활성 공통 정책(없으면 기본값)의 경고·임계 온도에 견준 값입니다. 냉각 대기는 메모리에만 있어 재기동하면 사라집니다.

**노드 응답의 GPU 축** — `gpuUsagePercent` · `gpuTemperatureCelsius` · `gpuPowerWatts` (21 → 24필드)

| 칸 | 뜻 | 카드가 여럿일 때 |
|---|---|---|
| `gpuUsagePercent` | GPU 사용률(%) | 카드 평균 |
| `gpuTemperatureCelsius` | GPU 온도(°C) | 가장 뜨거운 카드 |
| `gpuPowerWatts` | GPU 전력(W) | 카드 합 |

- **`0` 은 미계측이고 칸마다 따로입니다.** 세 값은 각각 다른 시리즈에서 오므로 한두 칸만 0 일 수 있습니다(예: 익스포터가 전력 카운터를 안 내면 `gpuPowerWatts` 만 0). 0 인 칸만 "-" 로 두십시오. 켜진 카드의 0°C·0W 는 물리적으로 없어 0 을 값으로 읽을 일은 없습니다.
- **`totalGpu = 0` 이면 GPU 인벤토리가 안 잡힌 것입니다** — GPU 가 없는 노드이거나 DCGM 시리즈가 끊긴 노드(9장 — 배포 서버가 이 상태)이고, 응답만으로는 둘을 구분할 수 없으니 세 칸을 똑같이 "-" 로 두십시오. `totalGpu > 0` 인데 셋이 0 이면 GPU 개수만 잡히고 사용률·온도·전력 시리즈가 빈 것입니다.
- **`reservedGpu` 는 실행이 끝날 때까지 남습니다.** CPU·메모리·디스크 예약은 실행 중이 된 지 10분이 지난 RUNNING 에서 빠지지만(사용률 메트릭이 대신 반영), `availableGpu` 는 `gpuUsagePercent` 를 보지 않고 `totalGpu` 에서 예약만 빼므로 GPU 예약은 RUNNING 인 동안 `COMPLETED`·`FAILED` 가 될 때까지 `reservedGpu` 에 남고 `availableGpu` 에서 빠집니다. 실행 중이 된 시각은 현재 결정의 전달 기록이 `RUNNING` 이 된 시각이고, 그런 기록이 없으면 배치 시각입니다(7장). 배치 24시간이 지난 SCHEDULED 는 전처럼 GPU 까지 풀립니다. `reservedGpu` 가 `totalGpu` 보다 크면(GPU 수집이 끊겨 카드가 0 장이나 실제보다 적게 잡혔거나, #151(10장) 전에 같은 카드에 겹쳐 배정된 워크로드가 함께 실행 중이면) `availableGpu` 가 음수로 보이고, 그동안 그 노드는 GPU 를 요구하는 배치를 자원 부족으로 받지 않습니다. GPU 를 요구하지 않는 배치(`requiredGpu = 0`)는 `availableGpu` 를 보지 않아 이 값에 막히지 않고, CPU·메모리·디스크와 나머지 제약으로 판정합니다(10장). 실행 계층이 끝을 알리지 않으면 카드가 계속 묶입니다. 카드를 잡은 워크로드는 `/workloads/page?assignedNodeId={nodeId}&status=RUNNING` 으로 찾고(주의 12), 실행 계층에서 끝난 것을 확인한 뒤 상태 변경 API 로 실제 결과에 맞춰 `COMPLETED`·`FAILED` 로 끝내십시오(7장 「상태 변경과 전달 기록」). 상태 변경은 실행 계층에 알리지 않으므로 아직 도는 워크로드를 끝내면 그 카드가 다음 배치에 다시 나가고, `FAILED` 로 끝낸 워크로드는 다음 스케줄 실행의 대상이 될 수 있습니다(주의 6·7).
- **노드의 `powerWatts`·`temperatureCelsius` 에는 섞지 않습니다.** IPMI 전력은 섀시 전체(GPU 포함)라 더하면 이중 계상이고, 섀시 온도는 센서 평균이라 카드 값과 뜻이 다릅니다. 그래서 IPMI 가 없는 GPU 노드는 `powerSource = NONE`·`powerWatts = 0` 인 채 `gpuPowerWatts` 만 값이 있을 수 있습니다 — 버그가 아니라 "섀시는 미계측, GPU 는 계측" 입니다.
- **통합 권고의 반납 후보 판정은 GPU 를 보지 않습니다**(CPU 사용률 기준). GPU 가 도는 노드가 반납 후보에 오르면 이 칸으로 확인하십시오.
- 새 세 키는 `networkTxBps` 뒤에 끼워 넣었습니다 — 기존 21키의 상대 순서는 그대로이고, JSON 키 순서에 의존하는 화면만 주의하십시오.

**노드 응답의 디스크 축** — `diskCapacityGb` · `reservedDiskGb` · `availableDiskGb` (24 → 27필드, 단위 GB 정수)

| 칸 | 뜻 | 자리 |
|---|---|---|
| `diskCapacityGb` | 파일시스템 총량. **`0` = 모름** | `totalGpu` 뒤(총량 축) |
| `reservedDiskGb` | 아직 메트릭에 반영되지 않은 배치분의 `requiredDiskGb` 합 — CPU·메모리 예약과 같은 규칙(SCHEDULED 중 배치 24시간이 지나지 않은 것과 실행 중이 된 지 10분이 지나지 않은 RUNNING). GPU 예약은 실행이 끝날 때까지 남습니다(위 GPU 축) | `reservedGpu` 뒤(예약 축) |
| `availableDiskGb` | 가용 디스크 = ⌊총량 × (1 − `diskUsagePercent`/100)⌋ − 예약, 0 하한(총량도 정수로 절삭한 값). 총량을 모르면 `0` | `availableGpu` 뒤(가용 축, 응답의 맨 끝) |

- **C1 디스크 검사와 같은 식입니다.** 디스크를 요구한 배치는 CPU·메모리·GPU 가 충분하고 `availableDiskGb < requiredDiskGb` 인 노드를 `INSUFFICIENT_DISK`("디스크 부족 (필요 NGB, 가용 NGB / 총량 NGB)")로 뺍니다. 그 문장의 가용·총량이 `availableDiskGb`·`diskCapacityGb` 이고 필요는 워크로드의 `requiredDiskGb` 입니다. CPU 등이 먼저 모자라면 `INSUFFICIENT_RESOURCE` 로 옵니다. 응답 값은 조회 시점이라 이력에 남은 배치 시점의 수와는 다를 수 있습니다.
- **`diskCapacityGb = 0` 은 "여유 없음"이 아니라 "모름"입니다.** 그 노드는 디스크 때문에는 빠지지 않고(다른 제약은 그대로 · 주의 11 의 `디스크검사 0.0`), `availableDiskGb`·`diskUsagePercent` 도 0 입니다. 이 세 칸을 함께 "-" 로 두십시오. `reservedDiskGb` 는 총량을 모르는 노드에도 그 노드에 배치된 워크로드의 요구량이 더해지므로 값이 있을 수 있고, 가용 계산에는 쓰이지 않으니 그대로 보이면 됩니다. 총량이 있는데 가용이 0 이면 실제로 가득 찬 노드입니다(가용 시리즈가 빠져도 이렇게 보입니다).
- CPU·메모리·GPU 처럼 총량·예약·가용 세 칸을 각 축의 제자리에 넣었습니다. 세 키가 한곳에 모이지 않고 세 군데(`totalGpu` 뒤 · `reservedGpu` 뒤 · 맨 끝)로 흩어지므로 JSON 키 순서에 의존하는 화면은 주의하십시오. 총량은 설정된 마운트 지점 하나(기본 `/`)의 시리즈이고, 다른 마운트는 세지 않습니다.

**엔진별 노드 배치 스코어의 뜻(배치 결정 `candidateScores[]` · 배치 이력)** — 같은 이름의 필드에 엔진마다 다른 값이 들어갑니다

배치 엔진이 후보 노드마다 매기는 다섯 항목별 스코어 `cpuScore`·`memoryScore`·`powerScore`·`temperatureScore`·`networkScore` 는 이름이 같아도 엔진마다 담는 값이 다릅니다. 각 스코어는 같은 후보 `details` 의 아래 키와 같은 값입니다. 배치 이력은 선택한 노드의 다섯 스코어만 남기고 `details` 는 저장하지 않으므로, 이력은 이 표로 읽으십시오.

| 스코어 필드 | `weighted` | `power` | `temperature` |
|---|---|---|---|
| `cpuScore` | `cpu여유` — 예약을 반영한 CPU 여유 | `cpu여유` — weighted 와 같은 식 | `자원여유` — CPU·메모리 여유의 평균 |
| `memoryScore` | `메모리여유` — 예약을 반영한 메모리 여유 | `메모리여유` — weighted 와 같은 식 | `자원여유` — `cpuScore` 와 같은 값 |
| `powerScore` | `전력여유` — 정격 대비 남은 전력(예약 반영) | `전력점수` — PEI·비낭비·실질 한계 전력 스코어를 설정 비율로 섞은 값 | `냉각점수` — 온도가 상한에 가까울수록 빠르게 낮아지는 값 · 온도가 상한을 넘으면 음수 |
| `temperatureScore` | `온도여유` — 온도 상한까지 남은 폭 | `열점수` — `냉각점수`(음수는 0 으로 자름)를 경고 온도부터 상한까지 갈수록 더 깎은 값 · 음수 없음 | `열적여유` — 온도 상한까지 남은 폭 |
| `networkScore` | `네트워크한산도` — 트래픽이 적을수록 높음 · 주의 9 의 세 조건이 맞으면 링크 품질과 섞은 값 | `네트워크한산도` — 트래픽이 적을수록 높음 · 링크 품질은 섞지 않음 · 총 스코어에는 쓰지 않음 | `열적균형` — 클러스터 평균 온도보다 시원할수록 높음 |
| 총 스코어 `totalScore`·`finalScore` | 다섯 스코어에 정책 가중치를 곱해 더한 뒤 선호 MDC·우선순위 배수를 곱함 · 가중치 합에 상한이 없고 배수도 곱해져 1 을 넘을 수 있음 | 전력·열 묶음과 CPU·메모리 묶음을 설정 비율로 섞음 · 정책 가중치는 묶음 안 비율로만 씀 | `열적여유`·`열적균형`·`냉각점수`·`자원여유` 를 엔진에 정한 비율로 섞음 · 정책 가중치를 쓰지 않음 |

- 전력·온도를 재지 못한 노드는 그 값에서 나온 스코어가 잰 값이 아니라 중립 0.5 입니다(temperature 는 `powerScore`·`temperatureScore`·`networkScore` 세 스코어). 전력은 쟀지만 정격이 0 인 노드는 엔진마다 다릅니다 — power 는 재지 못한 것으로 보아 `powerScore` 가 0.5 이고, weighted 의 `전력여유` 는 중립이 아닌 1.0 입니다(docs/15 5장 #1). weighted 는 정책의 전력 반영·온도 반영을 끄면 잰 노드도 0.5 입니다. 후보 상세에서는 power 의 `전력계측`·`온도계측`, temperature 의 `온도계측` 으로 가를 수 있고 weighted 에는 이런 키가 없습니다. 이력의 `nodePowerWatts`·`nodeTemperature` 가 0 이면 그 축에서 나온 스코어는 중립값입니다. 0 이 아니어도 위 경우에는 중립일 수 있습니다.
- 온도 상한을 쓰는 스코어(weighted·power 의 `temperatureScore`, temperature 의 `powerScore`·`temperatureScore`)은 그 결정의 온도 상한(정책 · 스케줄이 지정했으면 그 값) 대비 값입니다. 같은 엔진이라도 상한이 다른 결정끼리는 눈금이 다릅니다.

**이력을 모아 볼 때**
- 항목별 스코어와 `finalScore` 는 엔진이 같은 행끼리만 평균·비교하십시오. `/history/page` 는 `engineName` 필터로 거르고(`sort=finalScore` 로 정렬할 때도 먼저 거르십시오), 필터가 없는 `/history`·`/workloads/{id}/history` 는 받은 뒤 행의 `engineName` 으로 나누십시오.
- `engineName` 필터 값은 `GET /engines` 의 `id`(`weighted`·`power`·`temperature`)입니다. 클래스명인 `name`(주의 10)을 넣으면 400 이 아니라 0건입니다(`/history/exclusion-summary` 도 같습니다).
- 실패한 결정(`success=false` · 배치를 건너뛴 결정 포함)은 다섯 스코어와 `finalScore` 가 0 입니다. 스코어 평균은 `success=true` 로 거른 뒤 내십시오.
- weighted·power 의 `finalScore` 는 다른 워크로드끼리 견줄 값이 아닙니다. weighted 는 워크로드 우선순위·선호 MDC 배수와 그때의 정책 가중치가 곱해지고, power 는 그때의 정책 가중치 비율과 워크로드의 요구 CPU(`전력점수` 안의 실질 한계 전력 스코어)에 따라 달라집니다. 기간 집계는 항목별 스코어로 하되, power 의 `powerScore` 는 요구 CPU 에 따라, weighted 의 `networkScore` 는 링크를 섞었는지에 따라 달라집니다. 후보끼리 순위는 배치 결정 응답의 `candidateScores[].totalScore` 로 보십시오.
- 같은 `finalScore` 가 실행 상세·모의 실행의 결정 목록과 워크로드의 `schedulingScore`(그 워크로드를 배치한 결정의 스코어)에도 실립니다. 워크로드 응답에는 엔진이 없으니 그 워크로드 이력에서 가장 최근의 성공 행(`success=true`)의 `engineName` 으로 확인하십시오. 실패 행은 스코어가 0 이고 엔진이 다를 수 있습니다.
- 2026-09-07 까지(그날 포함) 기록된 행은 집계에서 빼십시오(`/history/page` 는 `from` 을 `2026-09-08T00:00:00+09:00` 으로). 그 행은 `engineName` 이 요청에 적은 값 그대로이거나, power 행의 `powerScore`·`temperatureScore`·`networkScore` 뜻이 이 표와 다르거나, 다섯 스코어가 선택한 노드가 아닌 후보 목록 첫 노드의 것이거나, 전력·온도를 재지 못한 노드의 스코어가 0.5 가 아닌 만점일 수 있습니다. 날짜는 이 수정들이 develop 에 들어간 날이라, 서버 배포가 늦었다면 그 뒤에 기록한 행도 같을 수 있습니다.

**`exclusionCodes`(배치 이력 · 실행 상세 결정)** — 후보 노드의 제외 사유 코드 집계 (이력 응답 22 → 23필드 · 실행 상세 결정 12 → 13필드)

| 항목 | 내용 |
|---|---|
| 형식 | JSON 객체 **문자열** — 예 `{"COOLING_DOWN":1,"INSUFFICIENT_RESOURCE":3}`. 키는 코드 이름 오름차순, 값은 그 코드로 빠진 후보 수. 서버는 객체로 내리지 않으므로 화면이 파싱합니다 |
| 무엇을 세나 | 모집단은 `candidateCount` 와 같습니다 — 핵심 메트릭을 못 읽거나 질의가 응답하지 않아 수집 단계에서 빠진 노드(`/nodes/excluded`)는 후보가 아니라 여기에도 없습니다. 적격 후보(선택된 노드 포함)는 세지 않고, 성공한 결정에도 나머지 후보가 빠진 사유가 남습니다. 엔진이 배치 시점에 본 집계라 실행 계층이 그 뒤 배치를 되돌린 건도 그대로 남습니다(실행 후 클러스터 상태 `clusterSnapshot` 과 달리 되돌리기를 반영하지 않습니다) |
| 한 노드에 사유가 여럿이면 | **먼저 걸린 제약 하나로만 셉니다**(자원 → 사용률 → 열 → 전력 → 링크 → MDC 예산 순, 배치 결정 응답의 `exclusionCode` 와 같은 규칙). 그래서 값의 합 = 부적격 후보 수, `candidateCount − 합` = 적격 후보 수입니다. 자원이 모자라면서 동시에 뜨거운 노드는 `INSUFFICIENT_RESOURCE` 로만 잡히므로 온도 계열 건수는 "온도가 유일한 걸림돌이었던 후보 수" 의 하한입니다 |
| `null` | 제외가 하나도 없는 결정, 그리고 이 필드가 생기기 전의 옛 행. 빈 객체 `{}` 는 오지 않습니다 |
| 코드 목록 | 배치 결정 응답의 `exclusionCode` 와 같은 14종 — `INSUFFICIENT_RESOURCE`·`INSUFFICIENT_DISK`·`CPU_USAGE_EXCEEDED`·`MEMORY_USAGE_EXCEEDED`·`COOLING_DOWN`·`TEMPERATURE_EXCEEDED`·`WORKLOAD_TEMPERATURE_EXCEEDED`·`PREDICTED_TEMPERATURE_EXCEEDED`·`POWER_HEADROOM_EXCEEDED`·`WORKLOAD_POWER_EXCEEDED`·`RUN_POWER_BUDGET_EXCEEDED`·`PREDICTED_POWER_EXCEEDED`·`MDC_LINK_DOWN`·`MDC_POWER_BUDGET_EXCEEDED`. 이름은 바뀌지 않지만 **늘 수는 있습니다** |
| 범위 | "어떤 사유가 몇 건" 을 세는 통계용입니다. 실행 상세(`/schedules/{id}/runs/{runId}`)의 결정 목록에도 같은 형식으로 실립니다(2장 주의 8). 두 응답이 같은 저장 값을 싣습니다. 후보별 상세(어느 노드가 어떤 사유)는 여전히 dryRun·단건 배치 응답에만 있습니다 |
| 필터 | `/history/page?exclusionCode=CODE` 는 그 코드로 **센** 후보가 하나 이상 있는 결정만 돌려줍니다 — 한 노드는 먼저 걸린 제약 하나로만 세므로(위 행) 자원이 모자라면서 뜨겁기도 했던 노드는 `TEMPERATURE_EXCEEDED` 로 걸리지 않습니다. 총건수(`totalElements`)는 **결정 수**이지 빠진 후보 수가 아닙니다 — 후보 수는 같은 필터·창의 합계 API `codes[CODE].candidates` 로 얻습니다(행 단위로는 각 행 `exclusionCodes` 의 그 코드 값). 코드 이름은 대문자 그대로 주고(소문자·모르는 값은 400 과 가능한 값 목록), 빈 값(`exclusionCode=`)은 필터를 걸지 않은 것과 같습니다. 문자열의 키 단위로 맞추므로 `TEMPERATURE_EXCEEDED` 로 물어도 `WORKLOAD_TEMPERATURE_EXCEEDED` 만 든 행은 걸리지 않습니다. `null` 행(제외 없음 · 옛 행)은 어떤 코드에도 걸리지 않습니다. `success=true` 와 조합하면 "성공했지만 다른 후보가 빠진 결정", `success=false` 와 조합하면 "그 코드가 걸림돌 중 하나였던 실패" 입니다. 한 번에 코드 하나만 받습니다. `createdAt` 이 같은 결정은 `id` 로 한 번 더 정렬되어 페이지 경계에서 겹치거나 빠지지 않습니다(1.1절 **보조 정렬**). 이 조건은 앞이 `%` 인 부분 일치라 인덱스를 타지 못해 이력 전체를 훑으므로, 이력이 크게 쌓이면 창(`from`·`to` — `createdAt` 인덱스)이나 `workloadId`·`scheduleRunId` 와 함께 거는 편이 빠릅니다. 모든 코드의 건수를 한 번에 보려면 아래 **합계** 행의 API 를 쓰십시오 |
| 합계 | `GET /history/exclusion-summary` 는 창·필터 안 결정의 집계를 서버가 더해 `{ from, to, decisions, withExclusions, skipped, codes }` 를 돌려줍니다. 필터는 `workloadId`·`success`·`engineName`·`scheduleRunId`·`from`·`to` 이고, 코드별로 이미 나뉘어 나오므로 `exclusionCode` 는 받지 않습니다. `codes` 는 등장한 코드만 이름 오름차순이고(없으면 빈 객체 `{}` — 위 「`null`」 항목은 이력 행 값의 규칙입니다) 값은 `{ decisions, candidates }` 입니다. **도넛·비율은 `candidates` 로 그리십시오** — 위 「한 노드에 사유가 여럿이면」 규칙대로 세어 코드끼리 겹치지 않으므로 모든 코드의 합이 창 안에서 빠진 후보 전체입니다(온도 계열은 여전히 하한). 코드별 `decisions` 는 한 결정에 코드가 여럿이면 코드마다 세므로 합이 `withExclusions` 를 넘을 수 있어 비율에 쓰면 안 됩니다. `decisions` 는 집계가 없는 결정도 세고, `withExclusions` 는 제외가 하나 이상 정상으로 읽힌 결정 수(`skipped` 는 들지 않음), `skipped` 는 집계 문자열 형식이 어긋나 건너뛴 결정 수(정상이면 0)입니다. 그래서 `decisions − withExclusions − skipped` 가 제외 없는 결정(옛 행 포함) 수입니다 |
| 합계의 창과 페이징 | 창은 `[from, to)` 이고 값 형식과 400 조건은 주의 5 와 같습니다. 페이징과 달리 **둘 다 비우면 최근 30일**이고, 한쪽만 비우면 그쪽은 경계가 없습니다. 응답의 `from`·`to` 가 실제 창입니다(경계 없음은 `null`, 기본 창은 서버 시간대 오프셋). **응답의 `from`·`to` 를, `null` 인 쪽은 빼고, 같은 필터와 함께 `/history/page?exclusionCode=CODE` 에 넘기면 총건수가 `codes[CODE].decisions` 와 같습니다.** 창을 넘기지 않았거나(페이징에는 기본 창이 없음), `to` 가 `null` 이라 그사이 새 결정이 쌓였거나, `skipped` 가 0 이 아니면(형식이 깨졌지만 코드 토큰은 든 행이 페이징에만 걸림) 어긋납니다 — 도넛과 목록의 수를 맞추려면 `to` 도 주십시오. 건수 질의와 집계 질의 사이에 새 결정이 적재되면 합계 안의 수가 드물게 한 건 어긋날 수 있고, 다시 부르면 맞습니다. 집계 열에 인덱스가 없으니 이력이 크게 쌓이면 창과 `workloadId`·`scheduleRunId` 로 좁혀 부르십시오 |

**화면이 할 일**
- 문자열이므로 파싱해서 읽습니다.
- 코드 → 라벨 표를 두되, 표에 없는 키는 버리지 말고 코드 이름 그대로 표시합니다(코드는 늘 수 있습니다).
- `null` 은 "제외 없음" 또는 "옛 행" 입니다 — 0 건으로 그리지 말고 빈 칸으로 둡니다.
- 새 키는 응답의 맨 끝입니다 — 이력 응답은 `createdAt` 뒤(기존 22키), 실행 상세의 결정은 `decidedAt` 뒤(기존 12키).
- 기존 키의 상대 순서는 그대로입니다.

**합계를 그릴 때**
- `skipped` 가 0 보다 크면 그 결정들은 `codes` 에 없습니다. 차트 옆에 "N건 집계 불가" 로 적고 0 으로 숨기지 마십시오.
- 조각에는 `candidates`(후보 수)를, 클릭해 연 목록의 제목에는 `codes[CODE].decisions`(결정 수)를 쓰십시오. 목록의 `totalElements` 는 결정 수입니다.
- 창이 `exclusionCodes` 도입(#82 배포) 이전을 덮으면 `decisions − withExclusions − skipped` 에 옛 행이 섞여 "제외 없음" 으로 단정할 수 없습니다.

**실행 필터(`/history/page?scheduleRunId=`)** — 실행 상세의 결정 목록을 배치 이력 쪽에서 보는 길

| 항목 | 내용 |
|---|---|
| 모집단 | 실행 상세(`/schedules/{id}/runs/{runId}`)의 `decisions[]` 와 같습니다 — 총건수(`totalElements`)가 그 목록의 길이와 같고, 결정의 `historyId` 가 이력 행의 `id` 와 같은 값이라 두 화면을 행 단위로 이을 수 있습니다(2장 주의 8). 실행 필터는 스케줄 범위를 보지 않고 실행 id 만으로 조회합니다 |
| 정렬·크기 | 실행 상세는 상한 없이 오래된 순(`createdAt` 오름차순, 같은 시각이면 `id` 오름차순)으로 싣습니다. 이쪽은 기본 최신순 · `size` 기본 20 · 최대 100 이므로 `sort=createdAt,asc` 를 주면 행 순서가 실행 상세와 같습니다 — 한 실행의 결정은 `createdAt` 이 같은 값일 수 있지만 `id` 보조 정렬(1.1절)로 페이지 경계에서 겹치거나 빠지지 않습니다. 한 실행의 결정은 100건을 넘을 수 있으므로(대상 상한 기본 500) 그때 전량이 필요하면 상한 없이 싣는 실행 상세의 `decisions[]` 를 쓰고, 행 대조는 `historyId` = `id` 로 하십시오 |
| 실행 id 가 비는 행 | 실행 없이 만든 결정(단건 배치), 스케줄 삭제나 실행 이력 보존 기간(기본 90일) 정리로 연결이 끊긴 결정은 실행 id 가 비어 있어 어느 값에도 걸리지 않습니다 — 결정 자체는 남지만 실행 단위로는 다시 모이지 않습니다 |
| 0 건일 때 | 없는 실행 id 는 오류가 아니라 빈 페이지입니다(숫자가 아닌 값은 400). 실행 상세의 `decisions[]` 가 원래 비어 있는 실행(배치할 대상이 없어 끝난 실행, `clusterSnapshot.status = NO_TARGET` 등)은 여기서도 0 건이 맞습니다. 그 밖에 화면에 남아 있던 실행 id 로 0 건이 나오면 스케줄 삭제나 보존 기간 정리로 그 실행 이력이 지워졌을 수 있습니다(같은 id 의 실행 상세도 404) — "결정이 없었다" 로 단정하지 말고 실행 상세를 다시 열어 확인하십시오 |

---

## 4. 전력 최적화 진단 — `/api/v1/scheduler/optimization` (3)

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET | `/power` | 현재 진단 — 클러스터·MDC 별·개선 항목 |
| GET | `/power/trend` | 추세. `from`, `to`, `step`. 구간 전체의 에너지 요약(`energy`) 포함 |
| GET | `/consolidation` | 통합 배치 권고 — 반납 가능 노드 후보(아래 주의 5) |

**화면을 붙일 때 주의할 것**

1. **계측하지 못한 노드는 계산에서 빠집니다.** 응답의 `measured`, `unmeasuredReason`, `excludedNodes` 가 그 사실을 함께 내려주므로 화면에 근거로 표시하십시오.
2. **통합 권고는 권고까지입니다.** 실제 이전은 하지 않습니다. 후보마다 옮길 워크로드 목록, 유휴 전력, 이전 비용, 순 절감, 불가 사유가 있습니다.
3. 추세는 조회 시점에 계산합니다. 저장된 시계열이 아니므로 구간이 길면 응답이 느립니다.
4. **구간 에너지(`energy`)는 지점 배열을 적분한 값이 아닙니다.** 직접 적분하지 마십시오.
5. **통합 권고는 받을 노드를 실제 배치와 같은 설정과 냉각 대기로 판정하고, 냉각 대기를 새로 걸지 않습니다.** 워크로드를 옮겨 보는 계산은 배치가 쓰는 설정(`aims.scheduler.power.idle-ratio` · `aims.scheduler.thermal.inertia-k` · `aims.scheduler.thermal.cooldown-minutes`)과 지금 걸려 있는 냉각 대기(3장 주의 16)를 그대로 봅니다. 그래서 통합 권고가 쓰는 정책(5장 정책 선택 ③·④ — 리소스 종류별 정책은 보지 않음)으로 배치할 때 `COOLING_DOWN`·`PREDICTED_TEMPERATURE_EXCEEDED`·`PREDICTED_POWER_EXCEEDED` 등으로 빠질 노드로는 옮기지 않습니다. 냉각 대기가 풀리면 노드 상태가 같아도 결과가 달라질 수 있습니다. 조회이므로 온도 상한을 넘은 노드를 만나도 냉각 대기를 걸지 않고, 그 노드는 그 조회에서만 받을 노드에서 빠집니다(`GET /constraints` C3 설명의 「일정 시간 냉각 대기로 잡아 둔다」는 배치 평가의 동작입니다).

**추세 응답의 `energy`**

구간 전체를 하나로 요약한 칸입니다. 최상위에 있고, 지점 배열에는 열이 늘지 않았습니다.

| 칸 | 뜻 |
|---|---|
| `consumedKwh` | 실측 전력의 소비 에너지. 실측 노드가 없으면 `null` |
| `reclaimableKwh` | 유휴 노드를 반납했다면 아꼈을 에너지 |
| `reclaimableEstimatedKwh` | 그중 실측이 아닌 값에서 온 몫. 미계측 노드는 정격으로 어림하므로 여기 듭니다 |
| `integratedFrom` / `integratedTo` | 실제로 적분이 걸친 구간 |
| `integratedHours` | 한 노드가 덮은 시간 중 가장 긴 것 |
| `integratedNodes` | 적분에 쓴 노드 수 |
| `gaps` | 이어 붙이지 않고 끊은 횟수 |
| `reason` | 값이 비거나 좁아진 까닭. 정상이면 `null` |

- **지점 배열을 적분하면 값이 적게 나옵니다.** 지점의 `totalPowerWatts` 는 그 시각 전력을 못 읽은 노드를 0 으로 채워 만든 합입니다. 그대로 적분하면 결손이 "0 W 를 썼다" 로 읽힙니다. 그 시각에 결손이 있었는지는 `totalNodes` 와 `evaluableNodes` 가 다른지로 압니다.
- **물은 구간과 잰 구간이 다를 수 있습니다.** 시계열이 늦게 시작했거나 중간이 비면 그만큼 짧게 적분합니다. 24시간을 물었는데 21시간치가 왔는지는 `integratedHours` 로 확인하십시오. `gaps` 가 0보다 크면 그만큼 시간이 빠져 있습니다.
- **소비와 회수 가능이 같은 방식으로 계산됩니다.** 둘 다 0 을 채우기 전의 원시 구간 시계열을 노드별로 사다리꼴 적분합니다. 회수 가능 쪽만 시각마다 가동 판정과 유휴 역산을 한 번 더 거칩니다.
- **`integratedFrom`·`integratedTo`·`integratedHours`·`integratedNodes`·`gaps` 는 회수 가능 적분 기준입니다.** 유휴 노드가 없던 구간에서는 `consumedKwh` 만 값이 있고 이 칸들이 비어 있을 수 있습니다.
- **표본을 잇는 간격은 `step` 의 두 배이고 하한이 5분입니다.** 그보다 멀어진 자리는 잇지 않고 빼므로, 그만큼 시간과 에너지가 빠집니다. `step` 을 크게 줘도 값이 나오도록 간격이 함께 늘어납니다.
- **`reclaimableKwh` 에는 추정이 섞일 수 있습니다.** 전력을 못 읽은 유휴 노드는 정격으로 어림한 값이 들어갑니다. 얼마나 섞였는지는 `reclaimableEstimatedKwh` 로 보고, 그 몫이 크면 실측 근거가 얇다는 뜻입니다.
- 계산이 실패해도 추세는 그대로 내려갑니다. `energy` 는 `reason` 만 담긴 채 값이 비어 있습니다.

---

## 5. 스케줄링 정책 — `/api/v1/scheduler/policies` (6)

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET | `/` | 목록 |
| GET | `/{policyId}` | 상세 |
| POST | `/` | 생성 |
| PUT | `/{policyId}` | 편집 |
| POST | `/{policyId}/activate` | 활성화 |
| DELETE | `/{policyId}` | 삭제 |


- 리소스 종류별로 **활성 정책은 하나**입니다. 활성화하면 같은 종류의 다른 정책이 자동으로 비활성화됩니다.
- **배치가 쓰는 정책은 이 순서로 고릅니다.** ① 스케줄이 지정한 정책(`policyId` — 스케줄 실행·실행 미리보기·저장 전 미리보기·모의 실행·저장 전 모의 실행) ② 리소스 종류가 같은 활성 정책(종류를 비운 워크로드·스케줄은 VM 으로 읽음) ③ 종류를 비운 공통 활성 정책 ④ 이름이 `default` 인 정책의 값(비활성이어도 값만 쓰고 상태는 바꾸지 않음 · 없으면 단건 배치·모의 배치·스케줄 실행은 새로 만들어 저장하고, 실행 미리보기(2장 주의 11·12)·모의 실행(2장 주의 14)·저장 전 모의 배치(3장 주의 15)·통합 배치 권고(4장)는 저장하지 않은 기본값을 씀). 같은 단계에 활성이 둘 이상이면 이름 사전순 첫 번째입니다.
- 필드는 가중치 다섯(CPU·메모리·전력·온도·네트워크), 임계 넷(CPU·메모리·온도·전력 사용률), 인지 스위치 셋(전력·온도·친화도), 경고 온도, 기본 엔진입니다.

---

## 6. 운영자 입력 원장 (22)

| 대상 | 경로 | 메서드 |
|---|---|---|
| 노드 정격 전력 | `/node-power-specs` | GET `/`(`nodeId` 부분 일치 · 대소문자 무시 필터) · GET `/{specId}` · POST `/` · PUT `/{specId}` · DELETE `/{specId}` |
| MDC 전력 예산 | `/mdc-power-budgets` | GET `/`(`mdcId` 부분 일치 · 대소문자 무시 필터) · GET `/{budgetId}` · POST `/` · PUT `/{budgetId}` · DELETE `/{budgetId}` |
| MDC 링크 | `/mdc-links` | GET `/`(`mdcId`·`status` 필터) · GET `/matrix` · GET `/{linkId}` · POST `/` · PUT `/{linkId}` · DELETE `/{linkId}` |
| 익스포터 대응 | `/node-exporter-mappings` | GET `/`(`nodeId` 부분 일치 · 대소문자 무시 필터) · **GET `/candidates`** · GET `/{mappingId}` · POST `/` · PUT `/{mappingId}` · DELETE `/{mappingId}` |

센서로 알 수 없는 값(노드 정격 W, MDC 총량 W, MDC 간 지연·대역폭)과 센서가 **어디에** 있는지(노드별 DCGM·IPMI `instance`)를 운영자가 입력하는 화면입니다.

- **정격이 등록되지 않은 노드는 기본값 500W 로 계산합니다.** 원장이 비어 있으면 전 노드가 같은 값을 쓰므로, 화면에 "미등록" 을 구분해 보여 주는 편이 좋습니다.
- **익스포터 대응은 노드 한 대의 GPU 개수·전력·온도가 저장소의 어느 `instance` 시리즈에 있는지 적는 원장입니다.** 프로메테우스의 `instance` 라벨은 익스포터마다 다릅니다 — 노드 익스포터 `host:9100`, DCGM `host:9400`, IPMI 는 중앙 주소(`ipmi-exporter:9290`)일 수 있습니다. 원장이 비어 있으면 수집기가 **포트를 뗀 호스트명이 같은 시리즈가 정확히 하나일 때** 그것을 쓰고, 그것도 안 되면 노드 `instance` 를 그대로 씁니다. 중앙 IPMI 처럼 호스트명이 다른 형태는 이 원장에 적는 길밖에 없습니다. `nodeId` 는 노드 목록의 `nodeId`(노드 익스포터 `instance`) 그대로이고, 노드와 같은 값은 뜻이 없어 400, 같은 노드 재등록은 409 입니다. `instance` 의 존재는 검증하지 않습니다 — 오타면 그 노드만 미계측으로 남습니다. 노드 식별자와 `instance` 는 대소문자를 가리며, 노드 식별자는 대소문자만 달라도 중복(409)입니다 — 철자가 다른 행은 쓰이지 않습니다. 값을 넣거나 지우면 **다음 수집부터** 반영되고 저장된 이력은 바뀌지 않습니다.
- **한 `instance` 는 한 노드에만.** 다른 행이 이미 쓰는 DCGM·IPMI `instance` 를 적으면 **409** 이고 응답 문장이 어느 노드가 쓰는지 알려 줍니다. 같은 시리즈를 두 노드에 넣으면 그 값이 두 번 세어져 클러스터 전력 합·에너지·GPU 총량이 부풀기 때문입니다. 수집기도 같은 충돌(원장이 다른 노드가 호스트명으로 얻는 시리즈를 가리키는 경우)을 보면 **관련 노드를 전부 미계측**으로 두고 WARN 로그를 남깁니다. 중앙 IPMI 익스포터가 노드를 `instance` 로 구분하지 못하는 구성(배포 서버가 그렇습니다 — `ipmi-exporter:9290` 하나)에서는 한 노드에만 적거나 비워 두십시오.
- 화면에서 고를 **후보 `instance` 는 `GET /node-exporter-mappings/candidates` 가 줍니다.** 저장소에 지금 있는 DCGM·IPMI 시리즈의 `instance` 목록(`dcgm[]`·`ipmi[]` — 한 줄 7필드 `instance`·`host`·`gpuCount`(DCGM 시리즈 수 = 카드 수, IPMI 는 `null`)·`assignedNodeId`·`assignedBy`·`claimedByNodeIds`·`ledgerNodeIds`)과 지금 수집 대상인 노드 `instance` 목록(`nodeInstances`)입니다. `assignedBy` 가 수집기가 그 시리즈를 어느 노드에 붙이는지의 근거입니다 — `LEDGER`(원장 행) · `HOSTNAME`(포트를 뗀 호스트명이 같은 노드가 정확히 하나 — 이 비교만 대소문자를 무시합니다) · `SAME_AS_NODE`(노드 `instance` 그대로) · `AMBIGUOUS`(둘 이상의 노드가 같은 시리즈를 얻어 수집기가 그 노드들을 자기 `instance` 로 되돌림 — 자기 시리즈가 있는 노드만 값이 남고 나머지는 미계측, `claimedByNodeIds` 가 당사자 전부) · `UNASSIGNED`(어느 노드도 못 얻음 — **원장에 적어야 이어짐**) · `UNKNOWN`(노드 목록 질의가 답하지 않아 배정을 계산할 수 없음 — 이때는 배정을 읽지 마십시오). 화면은 `UNASSIGNED`·`AMBIGUOUS` 만 골라 보여 주면 됩니다. `ledgerNodeIds` 는 원장에서 그 `instance` 를 적은 노드 전부입니다(배정 근거가 원장이 아니어도 행은 있을 수 있음). `ledgerOnlyDcgm`·`ledgerOnlyIpmi` 는 원장에 적혔으나 저장소에 없는 값(오타·익스포터 다운)이고, **그 질의가 답했을 때만** 채워집니다 — 답하지 않았으면 대조할 것이 없어 빈 목록입니다. **`unavailable` 에 질의 이름(`dcgm`·`ipmi`·`nodes`)이 있으면 그 목록은 답을 못 받은 것이라 비었다고 읽지 마십시오** — 비어 있고 `unavailable` 도 비어 있을 때만 "그 시리즈가 저장소에 없다" 입니다. 못 받은 질의가 뒷받침하는 칸(`nodes` → 배정 `UNKNOWN`, `dcgm`·`ipmi` → 그쪽 `ledgerOnly` 빈 목록)은 서버가 비워 둡니다. 배정은 수집기(노드 목록·전력 최적화 추세)와 같은 규칙의 역방향이라 화면이 보는 배정과 수집이 어긋나지 않습니다 — 단 노드 한 대의 전력 효율 추세는 그 노드만 단독 해석하므로 `AMBIGUOUS` 여도 값이 붙을 수 있습니다. `nodeInstances` 는 노드 목록 API 의 `nodeId` 와 같은 질의(`up == 1`)에서 오므로 그대로 맞대 볼 수 있고, 수집에 실패해 노드 목록에서 빠진 노드(`/nodes/excluded`)도 여기에는 남습니다. 후보에 없는 `instance` 도 원장에 넣을 수는 있습니다(존재를 검증하지 않음) — 그 행은 다음 호출에서 `ledgerOnly` 로 보입니다. 메트릭 값(사용률·전력)은 쓰지 않고 `count by(instance)` 의 라벨과 시리즈 수만 봅니다. 저장소 질의 세 건을 동시에 던져 상한(`metrics.query-timeout-ms`, 기본 5초) 한 번을 기다리며 캐시는 없습니다 — **화면 진입·새로고침·원장 저장 직후에만 부르고 폴링하지 마십시오.**
- MDC 링크의 상태가 `DOWN` 이면 그 MDC 로는 배치하지 않습니다. `UP`·`DEGRADED` 의 지연과 손실은 제약이 아니라 배치 점수에만 반영됩니다.
- 정책의 `affinityEnabled` 가 켜져 있고 워크로드에 `dataMdcId` 가 있을 때만 링크가 배치에 반영됩니다. 이때 후보 노드 상세(`details`)에 `링크품질`(같은 MDC 1.0 · 등록 링크는 지연·손실 기반 · **원장에 없는 원격 MDC 는 기본 0.5**)과 `링크등록`(1.0 등록됨 또는 같은 MDC, 0.0 원장에 없음)이 붙습니다. 원장에 없는 원격 MDC 는 배치에서 빠지지 않고 점수만 낮습니다 — 링크를 등록하면 측정값으로 바뀝니다. `링크등록` 은 **원장에 행이 있는지**만 뜻하므로 `DOWN` 링크도 `1.0` 입니다. 배치 가능 여부는 `eligible` 과 제외 사유로 판단하십시오. 사유로 분기해야 하면 문장(`exclusionReason`)이 아니라 **코드(`exclusionCode`)** 를 보십시오 — 문장은 사람이 읽는 값이라 문구가 다듬어질 수 있습니다. `dataMdcId` 는 앞뒤 공백을 지운 뒤 노드의 MDC 라벨과 **정확히 일치**해야 같은 MDC 로 봅니다(대소문자 차이는 미등록 원격으로 처리, 공백만이면 지정 안 함).

---

## 7. 배치 결정 전달 기록 — `/api/v1/scheduler` (4)

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET | `/dispatches` | 목록. `status`, `workloadId`, `scheduleRunId`, 페이징 |
| GET | `/dispatches/{dispatchId}` | 상세 |
| POST | `/dispatches/{dispatchId}/result` | 실행 결과 콜백 — **실행 계층이 호출**합니다 |
| GET | `/workloads/{workloadId}/dispatches` | 워크로드별 전달 이력 |

지금은 배치 결정을 기록하는 데까지 동작합니다. 실제 생성·이동은 실행 계층 API 가 준비되면 붙습니다.

**예약 취소와 전달 기록(3장 주의 13).** 운영자가 예약을 취소하면 그 워크로드의 **현재 결정**(같은 멱등키)의 전달 기록이 `ACCEPTED` 면 `FAILED` 로 마감하고 `message` **앞에** "예약 취소 — 운영자 요청({요청자 로그인 이메일})" 을 붙입니다(기존 비고는 " / " 뒤에 이어지고 500자에서 뒤가 잘리므로 표지는 항상 남음). 이메일은 같은 기록의 `createdBy`(단건 배치 요청자)와 같은 종류의 값이라 화면 노출 규칙도 같게 두십시오. 새 상태 값은 두지 않았습니다(1.2절 값 집합 불변) — 그래서 `GET /dispatches?status=FAILED` 에는 실행 실패와 예약 취소가 함께 나오며 서버 필터는 없고 `message` 표지로 구별합니다. 마감된 기록에 늦게 온 콜백은 `RUNNING`·`COMPLETED` 면 409, `FAILED` 면 200 무변경이라 대기로 돌아간 워크로드를 건드리지 못합니다. 옛 결정의 기록(같은 워크로드의 이전 배정)은 손대지 않습니다 — 배치된 워크로드를 단건 배치로 다시 배치했거나, 예전에 상태 변경이나 재시작 정리로 끝내 남은 옛 `ACCEPTED` 기록은 그대로 남고, 그 기록에 온 콜백은 기록만 갱신하고 워크로드는 바꾸지 않습니다(아래 「옛 결정의 기록과 콜백」 · 예약 취소는 이런 기록을 새로 만들지 않지만 이미 있는 기록도 정리하지 않음). 실행 계층에 취소를 알리는 훅은 자리만 있고(기록형 디스패처는 할 일 없음) 호출자의 DB 트랜잭션 안에서 불리므로 원격 구현체는 커밋 뒤로 미뤄야 합니다. 취소와 콜백이 동시에 오면 잠금이 없어 나중 커밋이 이깁니다(상태 변경 API 와 콜백 사이도 같음).

**옛 결정의 기록과 콜백.** 결과 콜백(`POST /dispatches/{id}/result`)이 가리키는 기록이 워크로드의 **현재 결정**이 아니면(기록의 요청 시각이 워크로드의 배정 시각보다 앞서면 옛 결정 — 재시작 정리와 같은 기준) 기록은 전이표대로 갱신하되(실행 계층이 말한 사실은 남김) 워크로드·재시도 횟수·배정은 바꾸지 않고, `message` 뒤에 "옛 결정의 기록 — 워크로드는 바꾸지 않음" 을 붙여 200 으로 답합니다(콜백에 `message` 가 있으면 그 값이 비고가 되고 그 뒤에, 없으면 기존 비고 뒤에 " / " 로 이어지며 500자에서 뒤가 잘리므로 500자에 가까운 `message` 뒤에서는 표지가 잘릴 수 있습니다). 현재 결정의 기록이면 전과 같이 워크로드도 함께 바뀝니다. 끝난 기록에 늦게 온 콜백은 위 규칙(같은 상태 200 무변경 · 다르면 409) 그대로입니다. 옛 결정의 열린 기록은 배치된 워크로드를 단건 배치로 다시 배치했거나, 단건 배치의 엔진 실패 뒤 다시 배치했거나(대기로 되살렸든 다음 스케줄 실행이 실패 워크로드를 그대로 잡았든), 배포 전에 상태 변경·재시작 정리로 남은 경우에 생기며 소급해 정리하지는 않습니다 — 그 기록은 그 기록에 `FAILED` 콜백(기록이 `RUNNING` 이면 `COMPLETED` 도)을 보내 닫을 수 있고 워크로드는 영향받지 않습니다. 전에는 옛 결정의 기록에 온 콜백이 현재 결정의 워크로드를 실행 중·실패·완료로 옮기고 재시도 횟수를 올렸습니다.

**재시작과 전달 기록.** 스케줄 실행 도중 서버가 다시 시작되면 기동 정리가 그 실행이 배치한 워크로드를 **현재 결정의 전달 기록**(요청 시각이 가장 늦은 기록이 배정 시각보다 앞서지 않는 것)에 맞춰 가릅니다. 실행 계층이 이미 받은 것(`ACCEPTED`·`RUNNING`)은 워크로드를 그대로 두고(배정·재시도 그대로 · 실행 이력은 지금처럼 실패로 마무리 · 그 뒤는 결과 콜백이나 예약 취소·상태 변경으로 끝납니다), 전달 중이던 것(`REQUESTED`)은 기록을 `UNAVAILABLE` 로 닫고 `message` 를 "재시작 — 전달 결과 모름" 으로 둔 뒤(전달 중이던 기록에는 비고가 없어 표지가 `message` 전부) 워크로드를 실패로 돌리며(배정 해제 · 재시도 +1), 기록이 없거나 옛 결정의 기록뿐이거나 이미 끝난 기록이면 전처럼 실패로 돌립니다. 전에는 전달 기록을 보지 않고 모두 실패로 돌려, 실행 계층이 받은 워크로드가 다시 배치되고 그 옛 기록이 열린 채 남았으며 전달 중이던 기록은 `REQUESTED` 로 남아 상태 변경(3장 주의 13)으로만 닫을 수 있었습니다. 기동 정리가 보는 범위(실행 권한이 잡힌 스케줄의 끝나지 않은 실행)는 그대로이고, 그 밖에서 `REQUESTED` 로 남은 기록은 정리하지 않습니다(3장 주의 13 의 상태 변경 경로로 풉니다).

**상태 변경과 전달 기록(3장 주의 1·13).** 상태 변경 API(`PATCH /workloads/{id}/status`)로 워크로드를 `COMPLETED`·`FAILED` 로 보내면 그 워크로드의 **최신 결정**(요청 시각이 가장 늦은 기록 · 대개 예약 취소가 보는 「현재 결정」과 같은 기록)이 열린 상태(`REQUESTED`·`ACCEPTED`·`RUNNING`)일 때 같은 끝 상태로 마감하고 `message` **앞에** "운영자 상태 변경 — {상태}({요청자 로그인 이메일})" 을 붙입니다(표지 규칙은 예약 취소와 같음). `RUNNING` 으로 바꾸는 것은 기록을 건드리지 않고, 기록이 없거나 이미 끝 상태면 그대로입니다. 그래서 이 경로로 실행 중이 된 워크로드는 자원 예약의 시작 시각이 없어 배치 시각을 기준으로 삼고(배치 10분이 지나면 CPU·메모리·디스크 예약이 빠짐), 뒤늦게 `RUNNING` 콜백이 오면 그 시각부터 10분 동안 CPU·메모리·디스크를 예약합니다(이미 빠졌으면 다시 잡힘 · 3장 「노드 응답의 GPU 축」). 새 상태 값 없이 같은 끝 상태로 마감하므로 `GET /dispatches?status=FAILED` 와 `status=COMPLETED` 에는 운영자 상태 변경분이 함께 나오며 `message` 표지로 구별합니다. 마감된 기록에 늦게 온 콜백은 기록과 같은 상태면 200 무변경, 다르면 409 입니다(`FAILED` 마감은 `RUNNING`·`COMPLETED` 가 409 · `COMPLETED` 마감은 `RUNNING`·`FAILED` 가 409). 실행 계층에 취소를 알리지는 않습니다(상태 변경은 결과를 대신 적는 경로). 전에는 워크로드만 바뀌고 기록이 `ACCEPTED` 로 남아, 기록형 디스패처 모드에서 이 API 로 끝낸 워크로드의 기록이 열린 채 쌓였습니다. 배포 전에 그렇게 남은 기록은 소급해 마감하지 않습니다.

전달 기록의 `scheduleRunId` 와 배치 이력의 `scheduleRunId` 는 이름이 같아도 가리키는 모집단이 다릅니다. 전달 기록은 성공한 결정에만 생기므로 같은 실행이라도 배치 이력 쪽이 실패 결정만큼 많습니다. 스케줄을 지운 뒤에는 방향이 또 갈립니다 — 배치 이력은 실행과의 연결이 끊겨 그 실행 id 로 0건이 되지만, 전달 기록은 실행 id 를 그대로 들고 있어 계속 조회됩니다(워크로드를 지우면 그 워크로드의 전달 기록은 함께 지워집니다). 두 목록의 건수를 맞대어 검증하지 마십시오.

---

## 8. 배치 전략 비교 — `/api/v1/scheduler/benchmark/placement` (1)

**방법론은 docs/14 에 있습니다.** 왜 이 판·대조군·지표·판정 규칙인가와 기준 시점의 봉인 값(규칙 축 8 · 기준선 · 다이제스트)은 docs/14 배치 전략 비교 방법론이 설명하고, 이 장은 응답의 형태와 살아 있는 장부(규칙 축 이력 · 기준선 재취득 · 판정 이력)를 맡습니다.

우리 엔진과 대조군(선착순 두 변종 · 엔진 전력식 공유 ΔP 단독 · 자원 최밀집 · 단순 규칙 · 무작위), 그리고 **엔진 설정 변종 7열**(자원전용 2 · 링크 게이트 on 1 · 한계 전력 눈금 3 · 유휴 역산 차단 1 — 그중 **override 열은 뒤의 5열**이고 열마다 `strategies[].engineConfigOverride` 가 바뀐 노브를 밝히며 그 값은 설정 다이제스트에 들어갑니다. 자원전용 2열은 정책 변종이라 override 가 없고 총량 짝 조건의 기준 집합에 듭니다)을 같은 입력으로 비교한 결과입니다. 셀당 열은 16 입니다. **연구 검증·성능 보고용이며 화면 연동 대상이 아닙니다.**

**판 10장.** main · adversarial · sparse · convex · misrated · convex-misrated(판정판) 여섯 장에 더해 `link`(주 결과판 노드 + `links:` 절 · 데이터 MDC 순환 배정 — 링크 점수를 섞는 엔진은 weighted 하나뿐이라 값이 갈리는 열은 `weighted`/`weighted-affinity` 쌍이며 링크는 판 파일이 원장 노릇을 하고 DB 원장은 여전히 0건), `main-independent` · `convex-misrated-independent`(짝 판과 노드 동일, `phaseMode: independent` · `seeds` 3 → `boards[]` 가 seed 별 3장, 서로 독립 복제라 Board 사이의 시각 지점을 합쳐 세지 않습니다), `concave`(주 결과판과 생성식 지수 0.625 만 다른 판). **판정·방향 판정 자격은 `convex-misrated` · `main` 두 판의 `power` 행에만 있고 그 밖의 판·열·지표는 전부 자격이 없습니다**(폐쇄형 — 판·열이 늘어도 이 문장은 바뀌지 않습니다).

**배포 검증에서 관측된 것(사전 등록된 관측, 주장 아님).** 출처: 배포 서버 jar `2961086ea77b6b48`, 2026-09-14, `heavytail` 분포, 판정 셀과 같은 좌표(부하율 60% · 전원 제어 있음) 셀. 아래 수치는 이 저장소의 증거 묶음(`build/benchmark-evidence`, 코드 기본 설정)과 다를 수 있으며 **이 관측 문단의** 원본은 배포 응답입니다 — 판정의 B·N 은 저장소 시험의 기본 설정 실행이고 배포 응답은 재현 확인용 참고입니다(아래 판정 기준선 소절).

- **민감도(교집합 위 지점별 차 — 규칙의 추정량).** `power`(기본 500W) 대 `power-s250` 의 유효 지점 교집합 |I'| = 12(양쪽 제외 0)에서 지점별 차의 중앙값은 주 결과판 8.29%p · 판정판 6.37%p · 오목 판 13.30%p 로 문턱 2.0%p 를 넘습니다 → **격차 수준이 눈금에 의존합니다.** 대 `power-s1000` 은 −9.40 / −7.56 / −6.24%p 로 부호가 반대이며, 열별 중앙값은 세 눈금에 대해 단조 증가합니다(주 결과판 8.27 → 17.54 → 25.98%, 판정판 7.17 → 13.47 → 20.66%, 오목 판 6.80 → 18.57 → 24.46%; 전 구간 폭 13.5~17.7%p). 부호(격차 > 0)는 전 눈금·전 판에서 같습니다. 이 관측은 **수준**의 눈금 의존까지이고, 판정량인 **두 실행의 Δ** 는 이번 실행으로 재지 못했습니다 — 이미 소비된 판정은 뒤집히지도 재확인되지도 않습니다.
- **위상 구성 3벌(seed 7 / 11 / 13, 중앙값 %).** 방향판 짝 `main-independent` 20.74 / 16.95 / 18.85(폭 3.79%p — 판정 문턱 2.0%p 의 1.9배), 판정판 짝 `convex-misrated-independent` 13.21 / 13.48 / 12.83(폭 0.65%p). legacy 판 값(17.54 / 13.47)은 두 범위 안에 있습니다 — 모순 없음의 뜻이지 일치의 증거가 아닙니다. 방향판에서는 단일 legacy 값이 문턱만 한 폭 안의 한 점이라는 사실을 판정 보고에 함께 적어야 합니다.
- **오목 판 결론(형식 그대로).** β=0.625 판의 같은 좌표 셀 `power` 행 격차 중앙값은 X = 18.57%p(β=1.0 판 Y = 17.54%p), 차 |X−Y| = Z = 1.03%p 로 문턱 2.0%p **미만**이며 부호가 같습니다. 역산 경로 기여 `power-idle-fixed − power` 는 오목 판 W = −3.17%p, β=1.0 판 +0.39%p — 크기가 8배이고 부호가 반대입니다. 이 판은 세 경로가 함께 바뀌므로 원인을 지목하지 않으며, 격차의 부호와 크기 등급이 β<1 생성식에서도 유지된다는 것까지만 말합니다.
- **판정 가능성(기준선 자료).** 판정 셀·방향 셀 모두 `power` 행 `gapVsPabfdPercent` 유효 지점 12/12(|I| ≥ 10 충족), 중앙값 13.47 / 17.54%.
- **링크 판.** `weighted` 와 `weighted-affinity` 는 배치율이 과부하 110% 셀에서만 갈리고(98.1132 → 98.0769), 총전력·격차·건당 전력·엔진 믿은 비용·온도 편차가 **8셀 전부**에서 갈립니다 — netScore 경로가 작동하며 그 효과는 노드 선택의 작은 이동입니다. 격차 중앙값 차는 30·60% 셀에서 +0.42 ~ +0.63%p, 90% 셀에서 +0.05 ~ +0.07%p 이고 과부하 셀은 `weighted` 쪽 짝이 없어 대조하지 않습니다 — **부호가 +** 이므로 게이트를 켠 열이 대조군 대비로는 더 벌어집니다(링크 품질을 총점에 섞으면 전력만 보던 선택이 흔들리기 때문이며, 이 판은 판정 자격이 없어 성패로 읽지 않습니다). 요구 대역폭 배정 `[0, 500, 2000]` 중 2000 을 받은 워크로드만 a-c 경로(1000Mbps)에서 50% 부족분을 뭅니다. 링크 없는 판 9장에서는 두 열이 전 지표(median·samples·valid) 동일합니다.
- **짝 제외 표기.** 반증판 90% 두 셀에서 `power-s250` 4곳·`power-s1000` 2곳·`power-s0` 2곳이 기준 집합과 건수가 달라 총량 짝에서 빠졌고 그 사실이 셀 문장에 실립니다. 이 문장은 `totalsComparable=true` 인 셀에도 붙을 수 있습니다. 판정 회차 1 리비전(배치 점수 PEI·비낭비 항의 실효 기저)에서는 같은 두 셀의 기준 집합 짝 지점이 5 → 6 으로 늘고 override 열 제외가 없어져 이 문장이 붙지 않습니다 — 전략 배치가 움직이면 짝 집합과 그 표기도 함께 움직인다는 예입니다.

응답의 `boards[].digest` 는 판 파일 내용(개행을 LF 로 정규화)의 SHA-256 앞 12자, `engineConfig.configDigest` 는 엔진 설정 정규 문자열의 해시입니다. 두 값이 같으면 판 파일과 엔진 설정이 같다는 뜻입니다 — 워크로드 분포 파일과 판정 규칙은 응답 다이제스트에 들어가지 않으니(분포는 이름으로만 지목), 두 표를 견줄 때는 같은 소스 리비전에서 낸 것인지를 함께 확인합니다. 설정 다이제스트에는 네트워크 노브(`engineConfig.network`)와 열별 override(`overrides=` 세그먼트)가 들어가며, 그 성분은 전부 응답에 노출됩니다. 판 다이제스트는 빌드 OS 와 무관하며 `git show HEAD:파일 | sha256sum` 으로 재계산할 수 있습니다. 2026-09-11 이전 Windows 빌드가 낸 표는 10장 변경 이력의 옛→새 대응표로 대조합니다. 재현 대조 시에는 `evaluatedAt`(응답 생성 시각)만 제외하고 본문을 비교합니다 — 그 외 비결정 필드는 없습니다(난수 시드 고정, 맵 필드는 키 정렬로 직렬화).

**캐시와 동시 실행 제한.** 응답은 `evaluatedAt` 을 빼면 입력(판 · 분포 · 판 파일 다이제스트 · 워크로드 파일 다이제스트 · 설정 다이제스트 · 판정 규칙 다이제스트)의 순수 함수라, 서버가 같은 입력의 본문을 메모리에 보관하고 두 번째 호출부터는 `evaluatedAt` 만 새로 찍어 돌려줍니다 — 저장소(DB)에는 여전히 아무것도 쓰지 않으며 재시작하면 비워집니다(운영 중 비우는 수단은 없습니다. 키 조합이 판 10 × 분포 2 = 20 으로 고정이라 — seed 는 판 파일 값이고 요청 축이 아닙니다 — 내부 상한 25 에는 닿지 않고, 판·설정이 모두 jar 안이라 변경은 곧 재배포입니다). 계산은 한 번에 한 건만 돌고, 계산 중 들어온 호출 중 **캐시에 없는 입력**만 기다리지 않고 **429** 와 한국어 사유를 받습니다 — 캐시에 있는 입력은 계산 중에도 200 이고, 모르는 판·분포는 계산 중에도 400 입니다(입력 검증이 캐시·동시 제한보다 앞섭니다). 재호출 간격은 보통 5~10초, 오래 걸리는 판은 20초까지(배포 서버 2 vCPU 실측: 첫 호출 main 약 9초, 일반 판 4.6~5.8초, 위상 독립 판 15초대)이며 `Retry-After` 헤더는 주지 않습니다(공통 오류 래퍼를 건드리지 않기 위해 — 간격은 `message` 안내를 고정값으로). 429 는 서버 로그에 WARN 으로 남습니다. 배포 서버는 단일 인스턴스라 이 제한은 프로세스 단위입니다. 재배포 직후에는 캐시가 비어 있어 판마다 첫 호출 한 번씩이 필요하고 그 사이 다른 입력은 429 를 받습니다(전 조합 예열 ≈ 분포당 75초).

---

#### 판정 규칙 축 다이제스트(연구 재현용 · 화면 영향 없음)

판정 규칙(지표 키·방향·역할, 부하율·지점 수·시드, 판 상수, 판정 문턱, 금지 조각, 절차 문장)은 축별 정규 문자열의 SHA-256 앞 12자로 봉인되어 계약 시험의 이력 표와 증거 묶음 매니페스트(`ruleAxisDigests`)에 실립니다. 사전 등록의 원칙은 "규칙 커밋이 결과 코드보다 앞" 이지만, 아래 표의 **사후 개정** 행은 배포 검증 수치를 본 뒤에 고친 규칙입니다 — 1차는 총량 열의 짝 조건 기준 집합(유효 표본의 정의)을, 2차는 부수 검정의 추정량 정의와 보고 의무를, 3차(2026-09-15)는 판정 셀 분포의 소급 명문화와 기준선 보존·재사용 의무를 더했고, 어느 것도 판정 셀·문턱·격차 정의·판정 자격은 건드리지 않았으며, 그 사실을 감추지 않고 적습니다. 표의 **규칙 보강** 행은 관측 수치가 개정 내용에 관여하지 않은 문장 보강(시점과 무관)으로, 사전 등록도 사후 개정도 아닌 세 번째 갈래입니다. **사후 개정(관측 계기)·다음 회차 사전 등록** 행은 관측을 계기로 세웠으나 소비된 판정을 움직이지 않고(그 회차는 지점 배열 부재로 이미 판정 불가) 다음 회차에 대해서는 결과 코드 앞에 등록된 개정으로, 판정의 엄격도를 **낮추지 않는** 방향일 때만 이 라벨을 씁니다(보고 의무만 더하는 개정은 엄격도가 그대로라 여기에 듭니다). **회차 전환 사전 등록** 행은 소비된 회차의 지목을 목록으로 내리고 다음 회차의 지목만 세우는 판정 없는 개정으로 judgment 축 행에만 쓰며, 그 전환을 인용만 따라가는 prose 행은 본래 라벨을 유지합니다. 응답에는 싣지 않습니다. 축이 바뀌는 커밋은 제목을 `rules:` 로 시작하고 결과 코드 변경을 같은 커밋에 담지 않으며, 아래 표와 시험의 이력에 행을 더합니다. 증거 묶음의 생성 방법은 매니페스트의 `regeneratedBy` 에 있습니다.

| 날짜 | 축 | 옛 → 새 | 사유 |
|---|---|---|---|
| 2026-09-14 | metrics · sweep · boards · measured · judgment · banned · prose | (없음) → 시험 이력 표의 현재 값 | 축 봉인 도입 |
| 2026-09-14 | boards | `2a0f6a76ba2f` → `9ab95b89dc3e` | 방법론 변경 사전 등록 — 오목 지수 0.625 · 오목/링크 판 id · 위상 독립 판 2장 · seed 수 3 · SplitMix64 혼합 상수 |
| 2026-09-14 | sensitivity | (없음) → `3741559c00dd` | 축 신설 — 한계 전력 눈금 3값(250·1000·0) · 역산 차단 열 · 링크 게이트 열 |
| 2026-09-14 | prose | `ae0fbae7a919` → `b37d0ec2006e` | 폐쇄형 판정 자격 문장 · 민감도 검정 · 위상 구성 · 오목 판 결론 형식 |
| 2026-09-14 | measured | `0189eec53480` (불변) | 판정 시점 고정값(316) 으로 의미 확정 — 현 실측(오목 판 345.5W)은 판 데이터라 축 밖 |
| 2026-09-14 | prose | `b37d0ec2006e` → `d06e13cc090c` | **사후 개정** — 배포 검증에서 변종 열 추가만으로 기존 열의 총량 표본이 5 → 1 로 줄어드는 것을 보고, 총량 짝 조건 기준 집합을 override 없는 기본 설정 열로 고정. 판정 문턱·판정 셀·격차 정의 불변 |
| 2026-09-14 | prose | `d06e13cc090c` → `45be90af10f3` | **2차 사후 개정** — 민감도 검정을 두 행의 유효 지점 교집합 위 지점별 차로 정정하고 수준량/판정량 구분을 명시, 위상 구성 범위 보고 의무, 오목 판 결론의 차·역산 기여 슬롯, 짝 제외 지점의 응답 표기 의무. 판정 셀·문턱·격차 정의·판정 자격 불변 |
| 2026-09-15 | prose | `45be90af10f3` → `7f4153f1a489` | 문장 명확화 — 민감도 "세 눈금" 을 기본 500W(`power`)·s250·s1000 으로 명시, s0 은 사다리 밖. 의미 변경 없음 |
| 2026-09-15 | judgment | `e1601b21aca2` → `cfb639b97e76` | **3차 사후 개정(소급 명문화)** — 판정 셀 분포 `heavytail` 을 축에 편입. 지금까지의 판정·관측이 전부 쓴 값이라 결과는 바뀌지 않고 앞으로의 선택 자유도만 닫힘 |
| 2026-09-15 | prose | `7f4153f1a489` → `4ecbed57aecb` | **3차 사후 개정** — 직전 배포 관측이 "소비된 판정의 지점 배열이 어디에도 없다" 를 드러낸 뒤: 기준선을 지점 배열째 저장소 파일에 보존·출처 인용·결측 적시, B·N 의 생산 경로를 저장소 시험 기본 설정 실행으로 고정(배포 응답은 참고), 기준선 재사용 순서, 사전 추정의 산출 방법 기재 의무. 판정 셀·문턱·격차 정의·판정 자격 불변 |
| 2026-09-15 | prose | `4ecbed57aecb` → `6f8bfe5c389e` | 사전 등록 — 규칙 축 대조 등급(판정 무효화 `judgment`·`sweep`·`boards`·`measured` / 부수 보고 무효화 `sensitivity` / 표기 `metrics`·`banned`·`prose`). 다음 판정의 출처 대조 절차 |
| 2026-09-15 | prose | `6f8bfe5c389e` → `8f6dfdcf02e2` | **규칙 보강** — 기준선 재취득 예외(무효화 축·판·워크로드를 바꾸는 PR 은 판정을 게시하지 않는 한 같은 PR 에서 기준선을 다시 뜬다), 이력 행 칸(엔진 동등 리비전·산출 HEAD), 판정량 비교는 4자리 반올림 차(부동소수 잡음 제거), 기준선 경로를 상수 이름으로 인용. 판정 셀·문턱·격차 정의·판정 자격 불변 |
| 2026-09-15 | judgment | `cfb639b97e76` → `4d55e10c3eb8` | **사후 개정(관측 계기)·다음 회차 사전 등록** — 지점 비교 무시 폭 0.0002(4자리 두 칸)와 다음 회차 판정 주어 편입. 한계 전력 곡선 평가점 정정의 증거 묶음 대조에서 실수 항등인 변경이 26칸을 한 칸씩 뒤집은 것(전부 순위 근거 아닌 표시 지표, 판정 근거 열은 바이트 동일 — 판정량에 대해서는 같은 기전의 외삽이라 예방으로 세움)을 본 뒤 세운 잡음 하한. 판정을 더 엄격하게 하는 방향. 지목이 무효화 축에 있어 판정 PR 은 바꿀 수 없음. 판정 셀·중앙값 문턱·격차 정의·판정 자격 불변 |
| 2026-09-15 | prose | `8f6dfdcf02e2` → `e8659f43d2f9` | **사후 개정(관측 계기)·다음 회차 사전 등록** — 3항 축소 지점에 무시 폭, 4항 방향 셀 중앙값 차에 그 두 배(중앙값 축은 반올림이 겹쳐 최대 두 칸 잡음 — 밴드는 두 칸 초과 최소 세 칸, 여유 한 칸을 더해 네 칸) 적용 문장. 판정 주어를 "회차마다 `rules:` 커밋이 하나로 지목한 결함"으로(다음 회차 지목은 `JUDGMENT_TARGET` 인용), 지목은 judgment 축에 있어 판정 PR 안에서 바꾸면 기준선 판정 불가(등급 규칙에 같은 단서, 지목 이력은 계약 시험이 봉인). 판정 셀·중앙값 문턱·격차 정의·판정 자격 불변 |
| 2026-09-16 | judgment | `4d55e10c3eb8` → `106525cdf288` | **회차 전환 사전 등록** — 다음 회차 판정 주어를 전력 엔진 배치 점수 열 항의 실측 온도 고정(예약 미반영)으로 교체. 회차 1 지목은 결말 `PASS` 로 소비 목록에 내림. 지목은 기저 불일치까지이고 열관성 계수(엔진 노브)의 온도 상승분 크기·깨우기항 유휴 역산·계측 게이트는 지목 밖. 지목 정정은 기본 설정 불변(설정 다이제스트가 다르면 판정 불가). 판정 셀·중앙값 문턱·격차 정의·판정 자격 불변 |
| 2026-09-16 | prose | `e8659f43d2f9` → `f60c6d7d04ff` | **사후 개정(관측 계기)·다음 회차 사전 등록** — 회차 1 보고에서 본 세 가지를 보고 의무로(엄격도 불변): 2항 옆에 지점별 차 중앙값 병기와 2항 미달까지의 최소 동률 지점 수(판정은 두 중앙값 차 그대로), 지점은 한 주기의 시각 지점이라 독립 복제가 아님, 판정 자격 두 판 판정 셀 밖 셀 격차의 증거 묶음 대조(손계산 항목). 소비된 회차 목록에 회차 1 문장 추가와 다음 회차 지목 인용 갱신. 판정 셀·중앙값 문턱·격차 정의·판정 자격 불변 |
| 2026-09-16 | judgment | `106525cdf288` → `3a9290f91dd7` | **회차 전환 사전 등록** — 다음 회차 판정 주어를 전력 엔진 한계 전력 식의 유휴 기준선 이원(동적 범위의 유휴 절편은 선언 유휴 · 깨우기 비용은 실측 역산)으로 교체. 열 항 실효 온도 기저 지목은 결말 `FAIL` 로 소비 목록에 내림. 정정은 계측된 유휴 노드에 한하고 역산 유휴 쪽으로 맞추며 새 노브 없음, 실측 상수 갱신은 지목의 산출물. 판정 셀·중앙값 문턱·격차 정의·판정 자격 불변 |
| 2026-09-16 | prose | `f60c6d7d04ff` → `7fad731af40a` | **사후 개정(관측 계기)·다음 회차 사전 등록** — 셀 밖 부수 보고를 판정 셀·방향 셀 자신을 뺀 셀로 명문화하고 기준선 파일(형식 3)의 전 셀 배열과 대조, 한쪽만 값이 있는 셀은 짝 집합 변화로 따로 적음, 지점별 차 중앙값·최소 동률 지점 수·셀 밖 대조를 회전 초안이 표로 냄, 매니페스트 gitHead 가 unknown 이면 entriesDigest 로 동일성 확인. 소비된 회차 목록에 열 항 문장 추가와 다음 회차 지목 인용 갱신. 판정 셀·중앙값 문턱·격차 정의·판정 자격 불변 |
| 2026-09-17 | prose | `7fad731af40a` → `10edf28f06f4` | **규칙 보강** — 셀 밖 대조의 지점별 차 중앙값도 판정량과 같은 I 제한으로 내고 표본 수를 병기하도록 명문화(PR #72 의 `rules:` 커밋 `aa3988b`). 회전 초안이 이미 그렇게 내고 있어 산출값은 불변이고 의무만 문장으로 올라옴. 계약 시험이 봉인했으나 이 표에는 빠져 있던 행을 회차 3 보고에서 보충 — 기준선 `17c8e496a4b5`(prose `7fad731af40a`)와 회차 3 N 사이의 표기 축 차이가 여기서 온다. 판정 셀·중앙값 문턱·격차 정의·판정 자격 불변 |
| 2026-10-06 | judgment | `3a9290f91dd7` → `3bd73f1c4650` | **회차 소비·다음 지목 공석** — 한계 전력 식 유휴 기준선 이원 지목은 결말 `FAIL` 로 소비 목록에 내리고 다음 회차 판정 주어는 공석(지목 없음)으로 둡니다. 공석인 동안 판정 PR 은 열리지 않으며 다음 지목은 결과 코드보다 앞선 `rules:` 커밋이 하나로 등록합니다. 판정 셀·중앙값 문턱·격차 정의·판정 자격 불변 |
| 2026-10-06 | prose | `10edf28f06f4` → `75a82028ba09` | **소비 목록 문장 추가·지목 인용 갱신** — 소비된 회차 목록에 회차 3 문장(정정 범위·실측 상수 단서는 소비 쪽 과거형으로)을 더하고 다음 회차 지목 인용을 공석으로 갱신. 산출값 불변, 판정 셀·중앙값 문턱·격차 정의·판정 자격 불변 |
| 2026-10-27 | judgment | `3bd73f1c4650` → `d9229f7925fd` | **공석 채움·다음 지목 등록** — 공석이던 다음 회차 판정 주어를 노드 배치 스코어 PEI 항의 코어 수 무시로 지정(소비 없음). 정정 범위·방향·지목 밖은 지목 문장 안에 두어 판정 무효화 축으로 잠금. 지목 정정은 기본 설정 불변. 판정 셀·중앙값 문턱·격차 정의·판정 자격 불변 |
| 2026-10-27 | prose | `75a82028ba09` → `6ceae632b3b6` | **지목 인용 갱신** — 다음 회차 지목 인용을 공석에서 지목 문장으로 갱신. 산출값 불변, 판정 셀·중앙값 문턱·격차 정의·판정 자격 불변 |
| 2026-10-27 | judgment | `d9229f7925fd` → `ae0b48b8f9a7` | **회차 소비·다음 지목 공석** — 노드 배치 스코어 PEI 항의 코어 수 무시 지목은 결말 `PASS` 로 소비 목록에 내리고 다음 회차 판정 주어는 공석(지목 없음)으로 둡니다. 공석 문장에 소비한 회차를 넣어 앞 공석(`3bd73f1c4650`)과 지문이 겹치지 않게 했습니다 — 겹치면 그때의 기준선이 무효화 축 대조를 통과합니다. 공석인 동안 판정 PR 은 열리지 않으며 다음 지목은 결과 코드보다 앞선 `rules:` 커밋이 하나로 등록합니다. 판정 셀·중앙값 문턱·격차 정의·판정 자격 불변 |
| 2026-10-27 | prose | `6ceae632b3b6` → `d259678dfd94` | **소비 목록 문장 추가·지목 인용 갱신** — 소비된 회차 목록에 회차 4 문장(정정 범위·평균 후보 범위는 소비 쪽 과거형으로)을 더하고 다음 회차 지목 인용을 공석으로 갱신. 산출값 불변, 판정 셀·중앙값 문턱·격차 정의·판정 자격 불변 |

#### 판정 기준선과 보고 절차(연구 재현용 · 화면 영향 없음)

**기준선 파일.** `src/test/resources/scheduler/judgment/judgment-baseline.json` — 판정 셀(`convex-misrated` · 60% · 전원 제어 있음)과 방향 셀(`main` · 같은 좌표)의 `power` · `power-s250` · `power-s1000` · `power-s0` · `power-idle-fixed` 다섯 열 `gapVsPabfdPercent` **지점 배열 12개(null 자리 보존)** 와 표본 수·중앙값, 위상 짝 판 2장의 seed 별 중앙값, 오목 판의 같은 좌표 두 중앙값, 출처(소스 리비전·판/워크로드/설정 다이제스트·규칙 축 8값·판정 상수·생산 경로)를 담습니다. 소비된 W 눈금 판정은 기준선을 중앙값 두 개로만 남겨 규칙 3·4항을 영원히 재구성할 수 없었고, 이 파일이 그 결손을 다음 판정부터 막습니다. 배포 jar 에는 실리지 않습니다.

- **생산 경로는 하나.** 증거 묶음 시험(`BenchmarkEvidenceDumpTest`)이 같은 실행에서 후보(`build/benchmark-evidence/judgment-baseline-candidate.json`)를 쓰고 매니페스트 `judgmentBaselineCandidate` 에 싣습니다. 판정 회전 시험(`JudgmentRoundTest`)은 현 실행 N 을 같은 헬퍼로 만듭니다 — B 와 N 은 저장소 시험의 코드 기본 설정 실행이고, 배포 응답은 재현 확인용 참고입니다.
- **재취득(2026-09-16).** 회차 2 지목 교체로 judgment 축이 돌아 `ab38cd38ad62` 는 B 자격을 잃었고, 같은 PR 에서 `27d5b1373690` 를 다시 떴습니다(산출 HEAD `f5b7d4c`, 두 셀 중앙값 10.7247 / 14.9318, 두 셀 `power` 지점 배열은 회차 1 회전 초안의 N 배열과 지점별 동일). 이 재취득은 회차 1 판정 게시 뒤의 정례 재취득을 겸합니다.
- **재취득·형식 3(2026-09-16).** 다음 지목 교체로 judgment 축이 다시 돌아 `27d5b1373690` 은 B 자격을 잃었고, 같은 PR 에서 `17c8e496a4b5` 를 다시 떴습니다(산출 HEAD `c543089`, 엔진 동등 리비전 `0f1eb88`, 두 셀 중앙값 12.4476 / 16.1995, 직전 B 와의 차 −1.7229 / −1.2677 은 전부 열 항 정정 귀속). 형식 3 은 판정 자격 두 판의 전 셀 `power` 행 배열(`outside`, 16 셀)을 함께 담아 셀 밖 대조가 워크트리 재실행 없이 파일에서 나옵니다. 회전 초안은 2단계에 지점별 차 중앙값·최소 동률 지점 수를 병기하고 표 G 로 셀 밖 대조를 냅니다. 이 재취득은 열 항 판정 게시 뒤의 정례 재취득을 겸합니다.
- **재취득(2026-10-06).** 회차 3 지목을 소비 목록에 내리고 다음 판정 주어를 공석으로 두어 judgment 축이 다시 돌아 `17c8e496a4b5` 는 B 자격을 잃었고, 같은 PR 에서 `8696f4da2e4d` 을 다시 떴습니다(산출 HEAD `616ea09` — 규칙 커밋 자체, 엔진 동등 리비전 `757a6b4`, 두 셀 중앙값 11.8396 / 16.6866, 직전 B 와의 차 +0.6080 / −0.4871 은 전부 회차 3 정정 귀속 — 증거 묶음 `0cafa69fbcc9` 가 회차 3 게시 시점과 같고 두 셀 중앙값이 게시된 N 값과 같습니다). 이 재취득은 회차 3 판정 게시 뒤의 정례 재취득을 겸하며, 회차 3 게시(09-21) 뒤 보름 늦게 수행된 것입니다 — 그 사이 `src/main` 머지는 엔진·판 생성 코드를 건드리지 않았습니다(스케줄 편집·미리보기·타 담당자 모니터링 PR).
- **재취득(2026-10-27).** 공석이던 다음 판정 주어를 지정해 judgment 축이 다시 돌아 `8696f4da2e4d` 는 B 자격을 잃었고, 같은 PR 에서 `98dea25bbe99` 를 다시 떴습니다(산출 HEAD `b6da077` — 규칙 커밋 자체, 엔진 동등 리비전 `a3a5ef3`, 두 셀 중앙값 11.8396 / 16.6866, 직전 B 와의 차 0.0000 / 0.0000 — 두 셀 `power` 지점 배열이 직전 기준선과 지점별로 같고 증거 묶음 `0cafa69fbcc9` 그대로). 직전 기준선 뒤 게시된 판정이 없어 정례 재취득은 아닙니다. 후보는 빌드 캐시를 끈 `./gradlew --no-build-cache cleanTest test --tests '*BenchmarkEvidenceDumpTest*'` 로 냈고, 출처 `gitHead` 가 규칙 커밋인지 확인한 뒤 복사했습니다.
- **재취득(2026-10-27, 회차 4 뒤).** 회차 4 지목을 소비 목록에 내리고 다음 판정 주어를 공석으로 두어 judgment 축이 다시 돌아 `98dea25bbe99` 는 B 자격을 잃었고, 같은 PR 에서 `1cd81ee0f858` 을 다시 떴습니다(산출 HEAD `d34725e` — 규칙 커밋 자체, 엔진 동등 리비전 `9df818e`, 두 셀 중앙값 9.7150 / 14.2874, 직전 B 와의 차 +2.1246 / +2.3992 — 전부 회차 4 정정 귀속, 두 셀 `power` 지점 배열이 회차 4 N 배열과 지점별로 같고 증거 묶음 `6d9356bed3f6` 그대로). 회차 4 게시 뒤의 정례 재취득을 겸합니다. 후보는 빌드 캐시를 끈 `./gradlew --no-build-cache cleanTest test --tests '*BenchmarkEvidenceDumpTest*'` 로 냈고, 출처 `gitHead` 가 규칙 커밋인지 확인한 뒤 복사했습니다.
- **봉인.** 파일 내용 다이제스트(개행 LF 정규화 · SHA-256 앞 12자)가 `JudgmentBaselineTest.BASELINE_HISTORY` 의 마지막 행과 같아야 시험이 통과합니다. 파일의 규칙 축 값은 산출 시점 값이라 표기 축(`prose`)은 현 리비전과 다를 수 있고, 회전 시험이 등급별로 처리합니다. 파일을 고치는 커밋은 행(다이제스트·날짜·사유·엔진 동등 리비전·산출 HEAD·직전 B 와의 두 셀 중앙값 차)을 더합니다. 산출 HEAD 는 파일 출처의 `gitHead` 와 일치해야 하며 시험이 그것을 봅니다(브랜치 커밋 해시라 머지 방식에 따라 히스토리에 남지 않을 수 있고, 그 사실을 행에 적습니다). 중앙값 차는 「직전 기준선 − 새 기준선」(양수면 격차 축소)이고, 앞 행과 그 행의 두 셀 중앙값으로 계산한 값(소수 넷째 자리)과 같은지도 테스트가 봅니다. 엔진 동등 리비전은 저장소 안에서 확인할 수 없는 리뷰 항목입니다.
- **재사용 순서(규칙 문장).** 판정 PR 은 기준선 파일을 수정하지 않습니다(B 는 N 이전에 머지된 파일 — 저장소 안에서 기계로 확인할 수 없어 PR 리뷰 항목입니다). 갱신은 판정 보고를 게시한 뒤 별도 커밋으로, 성패와 무관하게 직전 머지 리비전으로 합니다. 이력 행의 중앙값 차가 "판정 없는 변경이 기준을 얼마나 밀었는지" 를 남깁니다. **예외:** 판정 무효화 축이나 판·워크로드 정의를 바꾸는 PR 은 기존 기준선이 이미 판정 불가가 되므로, 그 PR 이 1~4항 판정을 게시하지 않는 한 같은 PR 에서 기준선을 다시 뜨고(증거 묶음 시험 1회 → 후보 복사 → 이력 행) 재산출 사유와 중앙값 차를 적습니다 — 판정과 기준선 갱신이 한 PR 에 함께 있으면 안 된다는 것이 규칙의 목적이고, 판정 없는 재산출은 그 목적을 건드리지 않습니다. `sensitivity` 축 변경은 기준선을 무효화하지 않습니다(민감도 표만 판정 불가). 갱신 커밋은 `test:` 로 하고 파일과 이력 행을 같은 커밋에 담습니다. 재취득 예외에서는 순서가 `rules:`(축 변경) → `test:`(후보 복사 + 이력 행)입니다 — 기준선 파일이 출처에 규칙 축 다이제스트를 싣기 때문에 규칙 커밋보다 먼저 뜬 기준선은 봉인 시험에서 옛 축 값으로 걸립니다.

**판정 보고 절차(규칙 함수 `judgeCell` · `judgeGap` · `medianOfPairedDifferences`).** 회전 시험(`./gradlew test --tests '*JudgmentRoundTest*'`)이 0~8단계 초안을 `build/benchmark-evidence/judgment-round-report.txt` 에 씁니다 — 결말이 판정 불가·실패여도 씁니다. 판정 PR 은 그 표를 PR 본문으로 옮기고 아래 판정 이력 표에 회차 행을 더합니다. 기준선이 낡았는지는 저장소 시험이 결말을 단언하지 않으므로 기계가 잡지 않습니다 — 배포 검증에서 판정 셀 `power` 중앙값을 기준선 파일 값과 대조해 차가 있으면 PR 본문에 적습니다.

0. 출처 대조 — 기준선의 규칙 축 8값을 현 실행과 **등급별로** 견줍니다. 판정 무효화 축(`judgment`·`sweep`·`boards`·`measured`) 불일치 = 1~4항 판정 불가, 부수 보고 무효화 축(`sensitivity`) 불일치 = 민감도 표만 판정 불가, 표기 축(`metrics`·`banned`·`prose`) 불일치 = 옛→새 값과 사유를 적고 진행. 판·워크로드 다이제스트가 다르면 판정 불가, 설정 다이제스트가 다르면(판정 대상 노브 변경) 옛→새를 적습니다.
1. 교집합 — |I| < 10 인 셀이 있으면 판정 불가(성공도 실패도 아님). 어느 실행의 어느 지점이 결측인지 적시합니다.
2. 표 A 판정 셀 — **I 제한** 중앙값 B/N(응답의 `median` 은 그 행의 유효 지점 전부라 다를 수 있음)·축소 폭(두 중앙값의 차를 4자리로 반올림 — 두 값이 이미 4자리라 반올림은 부동소수 잡음만 없앱니다)·문턱 2.0%p·축소 지점/필요(ceil(10/12 × |I|))·동률 수·악화 수(세 칸의 합이 |I|). 지점 축소는 차가 무시 폭 0.0002(4자리 두 칸) 이상일 때만 세고 그 미만은 동률, −0.0002 이하는 악화입니다 — 한 칸 차는 반올림 동점 뒤집힘과 구별되지 않기 때문입니다. 같은 줄에 지점별 차의 중앙값(B − N)과 2항 미달까지의 최소 동률 지점 수(모든 조합 중 최소 — 이미 미달이면 해당 없음, |I| 하한 미달이면 판정 불가)를 병기합니다.
3. 표 B 방향 셀 — 1항 충족과 round4(median B − median N) ≥ 무시 폭의 두 배 0.0004(중앙값 축은 반올림이 한 번 더 겹쳐 최대 두 칸 잡음 — 밴드는 두 칸을 넘어야 하고 여유 한 칸을 더해 네 칸. 그 밖의 크기 문턱 없음). 불일치면 전체 실패.
4. 표 C 민감도 — 눈금 열마다(`power` 500W · `power-s250` · `power-s1000`) B·N 을 **같은 열에서** 견준 Δ 를 나란히(판정량), 그리고 한 실행 안 `power` 대 `power-s250` 의 교집합 위 지점별 차 중앙값(수준량). 판정에 넣지 않습니다. 지점 수 칸은 판정과 같은 무시 폭으로 셉니다. `power-s0`(정격 분모)은 판정에도 표 C 에도 넣지 않습니다 — 규칙이 눈금 사다리 밖으로 둔 열이며, 변경 전 방식과의 수준 대조(관측)와 소급 관측의 재현을 위해 배열만 보존합니다(회전 시험이 표 C 열 목록에서 그 열을 배제하는 것을 단언합니다).
5. 표 D 위상 — 짝 판 2장의 seed 3값과 최소~최대 범위. 생략은 결격, 범위가 2.0%p 를 넘으면 단일 legacy 값이 그 폭 안의 한 점이라는 문장.
6. 표 E 오목 — X·Y·Z·부호·W 를 형식 그대로.
7. 표 F 짝 제외 — 열마다 null 지점 수(0 이어도 적습니다).
7-1. 표 G 판정 셀 밖 — 판정 자격 두 판의 전 셀 `power` 행 격차 중앙값 B(n) → N(n)(차 = B − N, 격차는 낮을수록 좋음), 판정 셀·방향 셀 자신은 제외하고 악화·축소·동률·짝 집합 변화·둘 다 짝 없음으로 분류해 집계합니다. 기준선 파일(형식 3)의 `outside` 와 대조하므로 재실행이 필요 없습니다.
8. 결말 한 줄(`UNJUDGEABLE` / `PASS` / `FAIL` 과 멈춘 조항) + 폐쇄형 판정 자격 재게시. 실패해도 되돌리지 않고 게시합니다.

**판정 주어.** 고치는 대상은 회차마다 그 회차의 `rules:` 커밋이 하나로 지목한 결함이며, 지목하지 않은 변경은 같은 회차의 판정에 얹지 않습니다. 소비된 회차의 지목은 깨우기 벌점이 유휴비로 고정되던 일률 편향(W 눈금 전환)과 배치 점수의 PEI·비낭비 항이 실측 CPU·실측 W 로 고정되어 예약을 반영하는 항과 기저가 갈리던 결함(회차 1, `PASS`)이고, 소비된 회차의 셋째 지목은 전력 엔진 배치 점수의 열 항만 실측 온도로 고정되어 같은 점수 안에서 예약을 반영하는 항(전력 3항의 사용률·전력 기저와 자원 여유 2항)과 기저가 갈리던 결함(회차 2, `FAIL` — 결함 정정은 옳으나 판정량은 확대)이고, 소비된 회차의 넷째 지목은 전력 엔진 한계 전력 식에서 유휴 전력의 기준선이 둘이라(동적 범위의 유휴 절편은 선언 유휴 정격 × 유휴 비율, 깨우기 비용은 실측 역산) 계측된 유휴 노드의 유휴가 한 식의 두 자리에서 서로 다른 값으로 쓰이던 결함(회차 3, `FAIL` — 판정 셀은 줄었으나 문턱 미달, 방향 셀 확대)이고, 소비된 회차의 다섯째 지목은 전력 엔진 노드 배치 스코어의 PEI 항이 실효 사용률을 실효 전력으로만 나눠 노드의 코어 수를 보지 않던 결함(회차 4, `PASS` — 판정 셀 11.8396 → 9.7150, 방향 셀 16.6866 → 14.2874)입니다. 그 정정은 배치 스코어용 PEI 의 분자에 한했고, 비율의 평균 코어 수는 배치 응답 `candidateScores` 와 같은 후보 전체(제외 판정·미계측 노드 포함)로 냈습니다. 지목 때 등록한 사전 추정(격차 축소 쪽 우세 · 포화 최댓값 0.581)·반증 조건과 그 대조는 아래 판정 이력 회차 4 행에 있습니다. 지목 밖 영향으로, 통합 권고의 이동 계산은 노드 하나를 뺀 후보로 전력 엔진을 불러 그 경로의 평균 코어 수가 실제 배치와 다릅니다(운영 서버는 전력 계측이 없어 전력 스코어가 중립값이라 영향 0). 다음 회차의 지목은 **공석**입니다 — 다음 판정 주어는 결과 코드보다 앞선 `rules:` 커밋이 하나로 지목해 `JUDGMENT_TARGET` 을 채우고, 그 커밋이 judgment 축을 돌리므로 같은 PR 에서 기준선을 다시 뜹니다. 공석인 동안 판정 PR 은 열리지 않으며, 품질 수정(음수 설정 가드 등 기본 설정에서 결과가 같은 변경)은 지목 없이 낼 수 있으나 판정을 게시하지 않습니다. 다음 지목 커밋은 결과 코드보다 앞서 사전 추정을 산출 방법과 함께 등록하고, 정정 뒤에도 설정 다이제스트가 그대로여야 합니다. 현재 기준선의 두 셀 중앙값은 9.7150 / 14.2874 이며, 다음 판정의 출발선은 다음 지목 PR 이 다시 측정한 기준선 파일로 확정합니다. 공석인 동안에는 증거 묶음 `entriesDigest` 를 바꾸는 변경을 미룰 필요가 없고, 다음 지목 커밋이 머지된 때부터 그 판정 PR 이 머지될 때까지 다시 미룹니다. 다음 회차 지목은 판정 무효화 축(`judgment`)에 편입되어 있어 바꾸면 기준선이 판정 불가가 되고, 판정을 게시하는 PR 은 기준선을 다시 뜨지 못하므로 그 PR 안에서 지목을 바꿀 수 없습니다. 지목 이력은 계약 시험이 봉인합니다. 운영 비용: 회차가 넘어갈 때마다 지목이 바뀌어 judgment 축이 돌므로, 매 회차 앞에 판정 없는 `rules:` + 기준선 재취득 PR 이 먼저 서는 것이 상설 절차입니다.

**판정 자격과 눈금 노브.** 기준선 파일에는 `power-s250` 열이 있어 눈금 노브를 250W 로 바꾸기만 해도 판정 셀 중앙값이 10.7247 → 7.0329 로 문턱을 넘는 것처럼 보입니다. 그러나 그 눈금(250W)은 판정 시점 고정값(`MAX_REAL_MARGINAL_WATTS_AT_JUDGMENT`, 316W)과 현 등록 판 실측 최댓값(`MAX_REAL_MARGINAL_WATTS`) 어느 쪽보다도 작아 점수가 0 으로 포화하는 변경이고, 규칙 문장("포화는 판정 대상이 아니다")과 계약 시험(기본 `marginalScaleWatts` 가 두 값 이상)이 이를 판정 자격에서 배제합니다 — 눈금 열은 민감도 표(표 C)의 재료일 뿐 판정 후보가 아닙니다.

**판정 이력.** 회차마다 한 행 — 판정 PR 이 그 PR 안에서 더하며, 실패·판정 불가도 적습니다. 비어 있으면 아직 이 절차로 소비된 판정이 없다는 뜻입니다.

| 회차 | 날짜 | B(기준선 이력 행) | N 리비전 | \|I\| 판정/방향 | 1~4항 | 결말 |
|---|---|---|---|---|---|---|
| — | 2026-09-15 | `3fef93c9862b`(첫 기준선 · 엔진 동등 리비전 `294dae4` · 산출 HEAD `f4094c4`) — 이후 judgment 축 회전으로 B 자격 상실 | (판정 없음 — 기준선 봉인만) | 12 / 12 | 항등 확인: B=B 는 축소 0·2항 실패 | — |
| — | 2026-09-15 | `ab38cd38ad62`(재취득 · 엔진 동등 리비전 `812e253` · 산출 HEAD `ca62b5f` · 직전 기준선과 중앙값 차 0.0000 / 0.0000 — 이후 judgment 축 회전으로 B 자격 상실) | (판정 없음 — 무시 폭 편입에 따른 재취득) | 12 / 12 | 지점 배열 첫 기준선과 동일 · 회전 초안은 축 차이 0(기준선이 낡지 않음)·항등 실패(성공 미선언)만 증명 | — |
| 1 | 2026-09-16 | `ab38cd38ad62`(엔진 동등 리비전 `812e253` · 산출 HEAD `ca62b5f`) | `be04d65`(회전 초안 산출 HEAD — 지목: 배치 점수 PEI·비낭비 항의 실효 기저. 이후 `src/main` 변경은 javadoc 1건) | 12 / 12 | 2항 2.745%p(문턱 2.0) · 3항 12/10(동률 0 · 악화 0) · 4항 충족(17.5399 → 14.9318) | `PASS` — 1~4항 충족. 판정 셀 13.4697 → 10.7247. 사전 추정은 "추정 없음(방향 불명)" 으로 등록되어 있었음. 규칙 축 8값 불변. 단, 판정량은 눈금 의존 — s250 0.1377%p(문턱 미달) · 기본 500W 2.745%p · s1000 2.9202%p. 관측: 지점별 차의 중앙값은 2.1202%p, 축소는 12지점 중 5지점에 몰려 두 지점이 동률이면 2항 미달(1.8797%p) |
| — | 2026-09-16 | `27d5b1373690`(재취득 · 정례 재취득 겸함 · 엔진 동등 리비전 `5e2da67` · 산출 HEAD `f5b7d4c` · 직전 기준선과 중앙값 차 2.7450 / 2.6081 — 전부 회차 1 정정 귀속, 이 재취득의 규칙 변경 기여 0 — 이후 judgment 축 회전으로 B 자격 상실) | (판정 없음 — 회차 2 지목 교체에 따른 재취득) | 12 / 12 | 두 셀 `power` 지점 배열이 회차 1 회전 초안의 N 배열과 지점별 동일 · 증거 묶음 `1753d94dad6b` 그대로 · 항등 실행은 축소 0·동률 12·`FAIL`(성공 미선언) | — |
| 2 | 2026-09-16 | `27d5b1373690`(엔진 동등 리비전 `5e2da67` · 산출 HEAD `f5b7d4c`) | `2bb0170`(판정 보고 초안 산출 HEAD — 지목: 전력 엔진 배치 점수 열 항의 실효 온도 기저. 이후 커밋은 시험·문서만) | 12 / 12 | 2항 −1.7229%p(문턱 2.0 · 확대) · 3항 2/10(동률 0 · 악화 10) · 4항 미충족(14.9318 → 16.1995) | `FAIL` — 2항. 판정 셀 10.7247 → 12.4476. 사전 추정 "2항 미달(확대) 우세 · 해석적" 과 일치. 규칙 축 8값·설정 다이제스트 `1487c10f4e97` 불변. 관측: 짝 차 중앙값 −2.2657%p(방향 셀 −1.4558, 악화 9 · 동률 1 · 축소 2) · 2항 미달까지의 최소 동률 지점 수는 해당 없음(확대라 어떤 지점을 동률로 돌려도 2항 충족 불가) · 판정 셀 밖 10셀 대조(직전 머지 리비전 `d4d4402` 증거 묶음 `1753d94dad6b` ↔ `2d8f4ab235a9`, 판정·방향 셀 자신은 표 A·B 에 있어 제외) 악화 8 · 축소 2(둘 다 전원 제어 없음 셀) · 방향판 110% 두 셀은 B 에서 짝 없음 → N 0.3033 — 과부하에서 전력 엔진 배치율이 내려와 기준 전략과 건수가 같은 지점 2곳이 생긴 짝 집합 변화라 축소·악화로 세지 않음 · 오목 판 X 14.9095 → 16.0398(확대) · 열관성 계수 0.10 / 0.15 / 0.20 판정 셀 11.0343 / 12.4476 / 13.1305(기본 계수 행만 B 와 견줄 수 있고 나머지는 대응 B 없는 수준값, 판정 아님) |
| — | 2026-09-16 | `17c8e496a4b5`(재취득 · 형식 3 · 정례 재취득 겸함 · 엔진 동등 리비전 `0f1eb88` · 산출 HEAD `c543089` · 직전 기준선과 중앙값 차 −1.7229 / −1.2677 — 전부 열 항 정정 귀속, 이 재취득의 규칙·형식 변경의 값 기여 0 — 형식 3 은 파일 다이제스트만 바꿈) | (판정 없음 — 다음 지목 교체에 따른 재취득) | 12 / 12 | 두 셀 `power` 지점 배열이 열 항 판정의 N 배열과 지점별 동일 · 증거 묶음 `2d8f4ab235a9` 그대로 · 항등 실행은 축소 0·동률 12·표 G 밖 셀 전부 동률·`FAIL`(성공 미선언) | — |
| 3 | 2026-09-21 | `17c8e496a4b5`(엔진 동등 리비전 `0f1eb88` · 산출 HEAD `c543089`) | `3365128`(코드 마지막 커밋 — 뒤 커밋은 문서만이라 산출값 동일, 증거 묶음 다이제스트로 확인 · 지목: 한계 전력 식 유휴 기준선의 이원. 결과 코드 커밋 `e337a80` 뒤 `src/main` 변경 없음 — 실측 상수 갱신은 그 `fix:` 커밋에 실었다) | 12 / 12 | 2항 +0.608%p(문턱 2.0 · 축소이나 미달) · 3항 4/10(동률 6 · 악화 2) · 4항 미충족(16.1995 → 16.6866, 방향 셀 악화) | `FAIL` — 2항. 판정 셀 12.4476 → **11.8396**. 사전 추정 "추정 없음(방향 불명) · 해석적(판정판 계측 유휴 노드 5 중 역산 유휴가 선언보다 낮은 3·높은 2 로 부호 갈림) · 노드별 변화 상한 32.56W · 이동 0 가능" 으로 등록돼 있었고, 관측은 판정 셀 축소·방향 셀 확대로 갈려 방향 불명 등록과 어긋나지 않음 · 반증 조건(상한에서 나올 수 없는 크기의 이동) 미발동. 0단계: 무효화 축·부수 보고 축 차이 0, **표기 축 prose `7fad731af40a` → `10edf28f06f4`**(PR #73 의 규칙 보강 — 위 이력 표에 보충) · 판·워크로드·설정 다이제스트 `1487c10f4e97` 동일. 증거 묶음 `ca1cffbf59e3` → `0cafa69fbcc9`. 대조군 pabfd 는 선언 유휴 선형 ΔP 라 불변 — 이동은 전부 엔진의 선택·점수 변화. 관측: 지점별 차 중앙값 판정 셀 0.0%p(동률 6/12) · 방향 셀 0.0%p(12지점은 한 주기의 시각 지점이라 독립 복제가 아님) · 2항 미달까지의 최소 동률 지점 수 해당 없음(이미 미달) · 판정 셀 밖 14셀 대조(악화 2 · 축소 6(그중 방향판 110% 두 셀은 |I| 1) · 동률 4 · 짝 집합 변화 0 · 둘 다 짝 없음 2 — 악화는 방향판 30% 두 셀) · 오목 판 X 16.5442 · Y 16.6866 · Z 0.1424(문턱 2.0 미만) · 부호 같음 · 역산 기여 W(오목) −0.7813 · W(β=1.0) 0.6851(역산 차단 열 `power-idle-fixed` 는 값 불변 11.7587 / 17.3717 이라 W 이동분은 `power` 이동분의 부호 반전이고, 정정 뒤 이 차는 동적 절편과 깨우기항 두 자리 전체의 역산 기여를 뜻함) · 눈금 의존(열별 |I| 12) s250 0.0%p · 기본 500W 0.608%p · s1000 0.1494%p(수준량 power−s250 5.5044%p · 제외 0) · 위상 짝 판 범위 main-independent 0.4474%p · convex-misrated-independent 0.6919%p · 짝 제외 열별 0 · 실측 상수 `MAX_REAL_MARGINAL_WATTS` 345.5437 → 348.2203(오목 판 bm-14 4코어, 눈금 500W 이하 · 판정 전제 316W 불변) · 한 실행 s0→power 차 판정 셀 3.2046%p(12/12) · 방향 셀 0.7081%p(9/12). 격차는 낮을수록 좋고 판정 셀은 줄었으나 문턱에 못 미치고 방향 셀은 늘었다 — 8단계대로 되돌리지 않고 게시. 판정 자격(폐쇄형): `convex-misrated` · `main` 두 판의 `power` 행만 |
| — | 2026-10-06 | `8696f4da2e4d`(재취득 · 정례 재취득 겸함 · 엔진 동등 리비전 `757a6b4` · 산출 HEAD `616ea09` · 직전 기준선과 중앙값 차 +0.6080 / −0.4871 — 전부 회차 3 정정 귀속, 이 재취득의 규칙 변경의 값 기여 0) | (판정 없음 — 회차 3 소비·다음 지목 공석에 따른 재취득) | 12 / 12 | 두 셀 중앙값이 회차 3 게시 N 값(11.8396 / 16.6866)과 같고 증거 묶음 `0cafa69fbcc9` 그대로 · 항등 실행은 축소 0·동률 12·악화 0·표 G 밖 셀 전부 동률·`FAIL`(성공 미선언) | — |
| — | 2026-10-27 | `98dea25bbe99`(재취득 · 엔진 동등 리비전 `a3a5ef3` · 산출 HEAD `b6da077` · 직전 기준선과 중앙값 차 0.0000 / 0.0000 — 규칙 변경의 값 기여 0) | (판정 없음 — 공석이던 판정 주어 지정에 따른 재취득) | 12 / 12 | 두 셀 `power` 지점 배열이 직전 기준선과 지점별 동일 · 증거 묶음 `0cafa69fbcc9` 그대로 · 항등 실행은 축소 0·동률 12·악화 0·표 G 밖 14셀 중 동률 12·둘 다 짝 없음 2·`FAIL`(성공 미선언) | — |
| 4 | 2026-10-27 | `98dea25bbe99`(엔진 동등 리비전 `a3a5ef3` · 산출 HEAD `b6da077`) | `04dce78`(코드 마지막 커밋 — 뒤 커밋은 문서만이라 산출값 동일, 증거 묶음 다이제스트로 확인 · 지목: 노드 배치 스코어 PEI 항의 코어 수 무시) | 12 / 12 | 2항 2.1246%p(문턱 2.0) · 3항 12/10(동률 0 · 악화 0) · 4항 충족(16.6866 → 14.2874) | `PASS` — 1~4항 충족. 판정 셀 11.8396 → **9.7150**. 사전 추정 "판정 셀·방향 셀 격차 축소 쪽 우세 · 크기 미정"(순위 상관 −0.27 → +0.55 · 방향판 −0.43 → +0.75 · pabfd 선택 순서 −0.19 → +0.81 · 포화 최댓값 0.581)이 지목 커밋 `b6da077` 본문에 등록돼 있었고 관측과 방향이 같음 · 반증 조건 둘(판정 셀 확대 · 포화 1건) 미발동 — 판정 자격 두 판 전 지점·전 계측 노드·예약 0~코어 전량에서 배치용 PEI 최댓값 0.5850(테스트로 확인). 0단계: 무효화·부수 보고·표기 축 차이 0 · 판·워크로드·설정 다이제스트 `1487c10f4e97` 동일. 증거 묶음 `0cafa69fbcc9` → `6d9356bed3f6`. 관측: 지점별 차 중앙값 판정 셀 2.691%p · 방향 셀 2.4641%p · **2항 미달까지의 최소 동률 지점 수 1/12**(한 지점만 동률로 돌아도 문턱 미달 — 판정량이 문턱에 붙어 있음) · 판정 셀 밖 14셀 대조(축소 10 · 악화 0 · 동률 0 · 짝 집합 변화 4 — 두 판 110% 네 셀 · 둘 다 짝 없음 0) · 오목 판 X 15.4319 · Y 14.2874 · Z 1.1445(문턱 2.0 미만) · 부호 같음 · W(오목) −0.2005 · W(β=1.0) 1.1528 · 눈금 의존(열별 |I| 12) s250 1.7479%p(문턱 미달) · 기본 500W 2.1246%p · s1000 4.3498%p(수준량 power−s250 3.4531%p · 제외 0) · 위상 짝 판 범위 main-independent 3.966%p(문턱 초과) · convex-misrated-independent 1.2428%p · 짝 제외 열별 0 · 열관성 계수 0.10 / 0.15 / 0.20 판정 셀 8.6348 / 9.7150 / 10.6035(기본 계수 행만 B 와 견줄 수 있음) · 한 실행 s0→power 차 판정 셀 4.2911%p(12/12) · 방향 셀 3.5082%p(10/12) · 배치 응답 기준 파일 두 시나리오에서 자원 부족으로 제외된 1코어 노드가 평균 코어 수를 낮춰 선택 노드가 뒤집힘(판정 아님). 판정 자격(폐쇄형): `convex-misrated` · `main` 두 판의 `power` 행만 |
| — | 2026-10-27 | `1cd81ee0f858`(재취득 · 정례 재취득 겸함 · 엔진 동등 리비전 `9df818e` · 산출 HEAD `d34725e` · 직전 기준선과 중앙값 차 +2.1246 / +2.3992 — 전부 회차 4 정정 귀속, 이 재취득의 규칙 변경의 값 기여 0) | (판정 없음 — 회차 4 소비·다음 지목 공석에 따른 재취득) | 12 / 12 | 두 셀 `power` 지점 배열이 회차 4 N 배열과 지점별로 같고 두 셀 중앙값이 게시 값(9.7150 / 14.2874)과 같음 · 증거 묶음 `6d9356bed3f6` 그대로 · 항등 실행은 축소 0·동률 12·악화 0·표 G 밖 14셀 중 동률 12·둘 다 짝 없음 2·`FAIL`(성공 미선언) | — |

**소급 보고 — 소비된 W 눈금 판정(관측이며 판정이 아닙니다).** 그 판정의 지점 배열 g_i(B) 는 어디에도 남아 있지 않아 규칙 3·4항은 **판정 불가**입니다. 1·2항에 해당하는 산술은 남아 있으나(커밋 본문의 두 중앙값 17.2043 / 21.0365 와 관측 13.4697 / 17.5399 의 차 3.7346 / 3.4966) 두 수가 다른 설정 다이제스트(`930f4e441ce1` ↔ `1487c10f4e97`)·판 다이제스트 정규화 전후의 리비전 산이라 같은 규칙의 두 실행이 아니므로 항 번호를 붙이지 않습니다. 별도의 **관측**으로, 기준선 리비전(`812e253`)의 `power-s0` 열(정격 분모 — 변경 전 방식)의 판정 셀·방향 셀 격차 중앙값은 17.2043 / 21.0365 로 기준선 커밋의 두 수와 소수 넷째 자리까지 같습니다(판정 회차 1 리비전에서는 이 열도 같은 전력 엔진을 타므로 12.1327 / 17.0299 로 움직였습니다 — 그 회차의 두 열 관측 줄은 회전 초안에 있고, 아래 문장은 기준선 리비전에 대한 것입니다). 이는 그 사이의 설정 다이제스트 변경과 판 다이제스트 개행 정규화가 이 두 셀의 값을 움직이지 않았다는 관측이며, 옛 실행의 재현도 소급 판정의 재확인도 아닙니다 — 규칙이 `power-s0` 을 "같은 자료를 재확인하는 열, 독립 증거 아님" 으로 규정하고, 견주는 대상이 두 리비전이 아니라 한 실행 안의 두 설정 열이기 때문입니다. 같은 실행 안에서 두 열의 중앙값 차는 판정 셀 3.7346%p(차가 양인 지점 12/12)·방향 셀 3.4966%p(차가 양인 지점 11/12)이지만, 이 수는 문턱·항 번호와 견주지 않고 판정 이력에도 넣지 않습니다(수준 비교라 이 문단의 지점 수에는 무시 폭을 적용하지 않았습니다 — 회전 초안의 관측 줄은 무시 폭으로 셉니다). `power-s0` 지점 배열은 기준선 파일에 보존되어 앞으로의 대역 비교에 쓰이고, 소급 판정의 성패는 게시된 대로 둡니다. 기준선 커밋의 사전 추정치(−3.73 / −3.50)는 관측 차와 소수 둘째 자리까지 같으나 **산출 방법이 기록되어 있지 않아** 해석적 예측이었는지 변경 코드 실행값이었는지 지금은 가릴 수 없습니다 — 앞으로의 사전 추정에는 산출 방법을 함께 적는 것이 규칙 문장에 들어갔습니다.

---

## 9. 지금 배포 서버에서 값이 비는 것

화면 개발 중 데이터가 없다고 의심되면 아래를 먼저 확인하십시오. 코드 문제가 아닙니다.

| 항목 | 현재 | 이유 |
|---|---|---|
| 노드 목록 | 1건 | 모니터링 대상 노드가 백엔드가 도는 서버 하나뿐 |
| 디스크 총량·가용 | 값 있음(총량 95 GB) | 노드 익스포터(`localhost:9100`)가 살아 있고 마운트 `/` 시리즈가 있다. 예약은 디스크를 요구한 워크로드가 없어 0 |
| 전력·온도 | 0 | IPMI 타깃(`ipmi-exporter:9290`) down. 살아나도 `instance` 가 노드와 다르므로 익스포터 대응 원장에 적어야 이어진다 |
| GPU 개수·사용률·온도·전력 | 0 | DCGM 타깃(`mdc1-gpu-node1:9400`) down. 살아나도 노드 `instance`(`localhost:9100`)와 호스트명이 달라 호스트명 일치가 걸리지 않는다 — 전력·온도와 마찬가지로 익스포터 대응 원장에 적어야 넷이 함께 이어진다 |
| 원장 4종 | 0건 | 운영자 입력 전 |
| 익스포터 후보 | `dcgm`·`ipmi` 빈 목록 · `unavailable` 빈 목록 | DCGM·IPMI 타깃이 down 이라 저장소에 그 시리즈가 없다. `unavailable` 이 비어 있으므로 "없음" 이 옳은 답이고, `nodeInstances` 는 노드 1대(`localhost:9100`)만 |
| 링크 원장 | 0건 | 운영자 입력 전. 그래서 배치 후보 상세에 `링크품질`·`링크등록` 키가 나오지 않는다(정책 `affinityEnabled` 도 꺼져 있음) |
| 통합 권고 | 후보가 적음 | 노드가 1대라 옮길 곳이 없음 |
| 실행 상세의 전력·온도 칸 | `null` | 전력·온도가 0 이라 실측 노드가 없음(`powerMeasuredNodes` 0). 가동 노드 수는 값이 나옴 |

---

## 10. 변경 이력

응답에 필드가 늘어나는 변경은 기존 계약을 깨지 않지만, 프론트가 키 집합을 고정해 두었다면 영향을 받습니다. 변경이 생기면 이 표에 먼저 적고 공유합니다.

| PR | 날짜 | 대상 | 변경 | 호환성 |
|---|---|---|---|---|
| #49 | 2026-09-10 | 배치 전략 비교 | `engineConfig.power.marginalScaleWatts` 추가, 설정 다이제스트 변경 | 필드 추가. 화면 영향 없음 |
| #51 | 2026-09-10 | 워크로드 | `lastFailedAt` 추가(21 → 22필드), 일괄 재개 엔드포인트 신설(58 → 59) | 필드 추가. 키 집합을 고정한 화면은 한 번 조정 필요 |
| #53 | 2026-09-11 | 배치 결과 후보 상세 | `affinityEnabled` 정책 + `dataMdcId` 워크로드 배치에서 `details.링크등록` 추가 · 원장에 없는 원격 MDC 의 `링크품질` 1.0 → 0.5(설정 `aims.scheduler.network.unknown-link-score`) | 필드 추가. 게이트를 켜지 않은 배치는 응답·점수 불변. 켠 배치는 미등록 원격 후보 총점 −0.0375(기본 가중치·NORMAL 우선순위·선호 보너스 없음 기준. CRITICAL 은 ×1.2, 정책 가중치·설정값에 비례) |
| #54 | 2026-09-11 | 배치 전략 비교 | `board.digest` 를 개행 정규화한 내용의 해시로. 판 yml·판정 규칙·계산은 무변경. Windows 빌드 jar 의 여섯 판 값이 저장소(LF) 기준값으로 바뀜 — main `add880e1f395`→`fb011848258b` · adversarial `4ce3ca80c771`→`b89c82edff1c` · sparse `cecd68498459`→`f2109dcf47e1` · convex `84f26146719d`→`5e71beea2592` · misrated `a1d780187595`→`e935750a1637` · convex-misrated `bdbbfd0f5b06`→`d199d46fa3b0`. LF 체크아웃 빌드는 불변 | 필드·형태 변경 없음. 값만 변경. 화면 연동 대상 아님 |
| #56 | 2026-09-14 | 배치 전략 비교 | 대조군 라벨 값 변경 — `pabfd` `전력 인지 최적적합(문헌 기준선)` → `전력 인지 최적적합(엔진 전력식 공유 · ΔP 단독 · 깨우기항 없음)`, `coolest-first` `최저 온도 우선(문헌 기준선 · 재평가 없음)` → `최저 온도 우선(단순 규칙 · 재평가 없음)`. Swagger 설명 문구 정정. 전략 id·지표 키·다이제스트 불변 | 필드·형태 변경 없음. `strategies[].label` 값만 변경 — 라벨을 키로 쓰는 화면은 조정 |
| #57 | 2026-09-14 | 배치 전략 비교 | 같은 입력의 두 번째 호출은 메모리 캐시에서(`evaluatedAt` 만 갱신), 계산 중 동시 호출(캐시 미적중)은 **429**(Swagger 응답 스키마에 200/400/429 선언). 맵 필드(`capacityByNode`·`probeExclusionReasons`) 키 정렬 직렬화. 판정 규칙 축 봉인(8장 소절 — 화면 영향 없음). 판 파일 `links:` 절 파서 추가(등록된 판은 절이 없어 다이제스트·값 불변). 판 등록부 레코드화 · 금지 문구 소스 검사 · 증거 묶음 시험 | 형태 변경 없음. 새 오류 코드 429 한 건 — 화면 연동 대상 아님. 맵 키 순서만 정렬로 고정(값 불변) |
| #58 | 2026-09-14 | 배치 전략 비교 | 엔진 설정 정규 문자열에 `network=`(링크 점수 노브 4)·`overrides=`(열별 노브) 세그먼트 편입 — `engineConfig.configDigest` **`79315785b762` → `1487c10f4e97`**. 판 파일·판정 규칙·기존 11열의 값은 불변 | 다이제스트 값 변경 — 이전 표와 견줄 때 이 대응표로 대조. `engineConfig.network` 필드 추가 |
| #58 | 2026-09-14 | 배치 전략 비교 | 판 4장 추가(`link` · `main-independent` · `convex-misrated-independent` · `concave`, 6 → 10) · 엔진 설정 변종 5열 추가(`weighted-affinity` · `power-s250` · `power-s1000` · `power-s0` · `power-idle-fixed`, 셀당 11 → 16열) · `strategies[].engineConfigOverride` · `boards[].phaseMode`·`phaseSeed` · `assumptions.workloadDataMdcAssignment` 추가 · override 열의 총량 짝 제외 지점을 `totalsIncomparableReason`/`invalidReason` 에 표기 | 필드 추가 + **열 순서 변경**. 새 5열이 엔진 변종 구간(인덱스 3~9)에 끼어 대조군 6열의 인덱스가 5~10 → 10~15 로 밀립니다. 열을 인덱스로 읽는 소비자는 조정이 필요하고, `strategies[].strategyId` 로 읽으면 영향이 없습니다(화면 연동 대상 아님 — 앞으로도 열은 id 로 읽습니다) |
| #58 | 2026-09-14 | 배치 전략 비교 | 위상 독립 판은 `boards[]` 가 seed 별 3장(기존 판은 길이 1 그대로). 캐시 상한 16 → 25, 429 재호출 안내 "보통 5~10초, 오래 걸리는 판은 20초까지" | 기존 판 호출 불변. 새 판만 `boards[]` 길이 3 |
| #60 | 2026-09-15 | 배치 결정 · 배치 전략 비교 | 한계 전력 동적항(`marginalPowerOnCurve`)의 곡선 위 평가점을 실측 사용률에서 **실효 사용률(예약분 반영)** 로 — 깨우기 여부(`isActive`)와 같은 기저입니다. 깨우기항의 유휴 역산은 실측을 유지합니다. **배치 결과와 모든 판정 근거 열은 불변**입니다: 제약 C1 이 적격 노드에 `u_실효 + ΔU ≤ 1` 을 보장해 두 기저가 갈리는 노드는 전부 부적격입니다. 증거 묶음 20건의 median·min·max 칸 118,272(빈 칸 제외 97,494) 중 **26칸**만 다르고 전부 `engineBelievedCostW`(순위 근거 아님)이며 대조군 2열(`ffd-size`·`ffd-priority-size`)·`temperature` 열·부록 판 3장에 한정됩니다(전력 엔진 5열 0칸, 판정 자격 2판 바이트 동일). 그 차는 ±0.0001 — binary64 에서 `(u+ΔU)−u` 가 평가점에 따라 1 ULP 달라지고, 지점값을 4자리로 먼저 반올림하는 집계에서 동점(`x.xxxx5`)에 앉은 칸이 결정론적으로 뒤집힙니다(min·max 는 n/32 지점값 자체, median 은 반올림된 두 지점값의 평균이 다시 동점에 앉는 경우). 재실행해도 같은 칸에서 재현됩니다(`entriesDigest` `bc6cd57606a2` → `f986e36f1cb5`) | 필드·형태·응답 다이제스트 변경 없음. **예약이 0 인 배치 요청은 응답이 바이트 단위로 불변입니다.** 한 실행에서 예약이 쌓이는 요청(스케줄 실행 루프·통합 권고 샌드박스)은 자원 부족으로 제외된 후보의 `details` 점수와 후보 배열 정렬 순서가 달라질 수 있고, 예약으로 실효가 100% 에 포화한 부적격 노드는 동적항 0 이라 전력 점수가 최고로 표시됩니다(제외 사유는 자원 부족). 적격 노드 값은 수학적으로 동일하나 binary64 마지막 비트가 반올림 경계를 지나므로, 통합 권고의 `moves[]` 비용과 총합(2자리 반올림) 같은 표시값도 동점 칸에서 ±0.01 달라질 수 있습니다. 선택 결과와 반납 판정은 바뀌지 않으며 — 무조건문이 아니라 관측 범위의 진술입니다 — 비교 판 20건 전 지점에서 배치 지표가 동일했습니다. 배치 이력(DB)은 선택 노드의 점수만 저장하므로 영속 데이터는 영향이 없습니다 |
| #62 | 2026-09-16 | 배치 결정 · 배치 전략 비교 · 통합 권고 | 배치 점수의 PEI·비낭비 두 항의 기저를 실측 CPU·실측 W 에서 **실효 사용률·실효 전력(예약분 반영)** 으로 — 한계 전력 동적항·가동 판정·자원 여유와 같은 기저입니다(`placementPei`·`placementWasteScore`). 진단 API(전력 효율·최적화)의 PEI·비낭비는 실측 그대로입니다. **컨텍스트의 예약 합이 0 이면 비트 단위로 불변**이고(배치 대기·메트릭 미반영 워크로드가 있으면 단건 배치도 DB 예약이 실려 값이 달라집니다), 한 실행에서 같은 노드에 워크로드가 잇달아 얹히는 경로(비교 하네스·통합 권고 샌드박스)에서 전력 엔진 5열(`power`·`power-s250`·`power-s1000`·`power-s0`·`power-idle-fixed`)의 값과 선택이 달라집니다 — 판정 자격 2판 `power` 행 판정 셀 13.4697 → 10.7247 · 방향 셀 17.5399 → 14.9318(판정 회차 1, 8장 판정 이력). 다른 열의 배치는 그대로이나 셀 단위로 함께 내는 지표는 따라 움직입니다: 반증판 90% 두 셀의 총량 짝 지점 5 → 6(총전력·가동 노드 증감·경고 구역·온도 편차의 표본 수와 값), 계측 공백판 90%·110% 셀의 미계측 비율 전략 간 최대 격차. 통합 권고는 옮길 대상이 바뀌면 누적 비용과 `NET_SAVING` ↔ 손해 차단 판정·권고 문구까지 뒤집힐 수 있으며 배포 서버(노드 1대·전력 0)에서는 관측할 수 없습니다. 영향 경로는 예약이 쌓이는 네 곳(비교 하네스·통합 권고 샌드박스·다건 배치·스케줄 실행 루프)이고, 직전 행과 달리 **적격 노드의 선택이 바뀌므로 저장되는 배치 이력의 선택 노드·점수도 달라집니다**. 후보 상세의 `PEI`·`비낭비` 칸은 예약이 쌓인 실행에서 **배치용 실효값**이라 전력 효율 평가 API 의 PEI 와 다른 수일 수 있습니다(같은 이름, 다른 기저). 판정량 이동의 귀속은 적격 노드에서 PEI 항 단독입니다(비낭비는 적격 노드 항등, 부적격 후보의 표시값만 갈림). 부수 효과: 예약이 쌓일 때 자원 항의 반몰림 신호를 PEI 항이 일부 되돌립니다(CPU·메모리 함께 12.5% 예약 시 총점 변화 −0.0125 → −0.0054) | 필드·형태·다이제스트 변경 없음. 값 변경 — 비교 판 화면·통합 권고 화면·배치 이력이 연동 대상. 컨텍스트 예약 합 0 이면 응답 불변. 전력 미계측 노드는 전력항 0.5 고정이라 이 보정을 받지 않아 계측·미계측 혼재 클러스터에서는 예약이 쌓일수록 계측 노드 쪽으로 점수 차가 벌어질 수 있음(추정). 배치 이력의 노드 상태 칸은 실측이라 저장된 전력 점수를 그 행만으로 재현할 수 없음 |
| #64 | 2026-09-16 | 배치 결정 · 배치 전략 비교 | 배치용 PEI(`placementPei`)의 가드. 기본 설정(정규화 상수 0.5)에서는 응답·값 전부 불변 — 증거 묶음 20건 다이제스트 `f986e36f1cb5` → `1753d94dad6b`(직전 회차에서 이동) 이후 이 PR 에서 그대로(기본·override 설정 한정). 정격을 모르는 노드(정격 0)는 예약이 있어도 중립값 0.5(비낭비와 대칭). PEI 정규화 상수(`aims.scheduler.power.pei-normalizer`)가 0 이하이면 배치·진단 두 경로가 한 대체값 0.5 로 정규화 — 엔진 경로는 방어가 없어 0 이면 사용률 > 0 노드는 PEI 1.0 포화, 사용률 0 노드는 NaN 이 반올림에서 0.0 으로 삼켜져 전력점수·총점 최하위, 음수면 점수가 음수였음 | 필드·형태·다이제스트 변경 없음. 설정을 0 이하로 둔 배포에서만 값 변경(그 경우 전력 엔진 전 열이 같이 바뀌어도 설정 다이제스트는 원시값이라 그대로 — 유효값화는 별도 PR). 정격 0 노드는 엔진이 전력항을 0.5 로 고정하고 `PEI` 칸을 비우므로 응답 불변 |
| #67 | 2026-09-16 | 배치 전략 비교(판정 규칙) | 판정 규칙 문자열과 시험 기준선만 변경(회차 2 판정 주어 교체·보고 의무·기준선 재취득 — 값은 8장 표) — 응답 필드·형태·값·`configDigest`·판 다이제스트 불변, 비교 결과 캐시 키만 회전 | 변경 없음. 화면 영향 없음 |
| #68 | 2026-09-16 | 배치 결정 · 배치 전략 비교 · 통합 권고 | 전력 엔진 배치 점수의 열 항(냉각·경고 감점) 입력을 실측 온도에서 **실효 온도(예약분 반영, `ThermalModel.effectiveTemperature`)** 로 — 전력 3항·자원 여유 2항과 같은 기저. 온도 엔진과 배치 후 예상 온도는 이미 실효 온도였고 계측 게이트·진단 API 는 실측 그대로. 컨텍스트의 예약 합이 0 이면 비트 단위로 불변, 예약이 쌓이는 경로(비교 하네스·통합 권고 샌드박스·다건 배치·스케줄 실행 루프)에서 전력 엔진 5열의 값·선택과 후보 상세 `열점수` 칸이 달라짐 — 판정 자격 2판 `power` 행 판정 셀 10.7247 → 12.4476 · 방향 셀 14.9318 → 16.1995(판정 결말 `FAIL`, 8장 판정 이력). 다른 열의 배치는 그대로이나 셀 단위 지표(반증판 90% 두 셀의 총량 짝 지점 6 → 7, 계측 공백판 90%·110% 셀의 미계측 격차)는 따라 움직임. 기본 계수(0.15)에서 예약 노드는 실효 온도가 올라 열점수가 내려가므로 반몰림 방향의 변경이며, 열점수 이동은 열관성 계수(`inertiaK`, 엔진 노브)에 1차 비례하지만 판정량은 배치 전환을 거쳐 비비례. 격차는 낮을수록 좋고 이 수는 **커졌습니다** — 이 PR 은 점수 항의 기저를 맞춘 것이고 판정량은 나빠졌으며 8장 8단계대로 되돌리지 않습니다. 다음 기준선은 이 값으로 갱신되므로 다음 판정의 출발선은 12.4476 / 16.1995 입니다. 통합 권고는 예약 노드 감점으로 옮길 대상이 흩어져 누적 비용이 오르는 쪽으로 움직이며 `NET_SAVING` ↔ 손해 차단 판정·문구가 뒤집힐 수 있습니다. 배치 이력의 온도 칸은 실측이라 저장된 열점수를 그 행만으로 재현할 수 없습니다. 과부하(110%) 셀에서는 전력 엔진 배치율이 소폭 움직입니다(판 10장 중 9장, −0.8 ~ +1.9%p) | 필드·형태·다이제스트 변경 없음. 값 변경 — 비교 판 화면·통합 권고 화면·배치 이력 연동 대상. 예약 합 0 응답 불변 |
| #69 | 2026-09-16 | 배치 전략 비교(판정 규칙) | 형식 3 재취득 — 판정 규칙 문자열과 시험 기준선만 변경(다음 판정 주어 교체 · 셀 밖 보고 의무 · 기준선 형식 3 — 값은 8장 표) — 응답 필드·형태·값·`configDigest`·판 다이제스트 불변, 비교 결과 캐시 키만 회전 | 변경 없음. 화면 영향 없음 |
| #72 | 2026-09-17 | 워크로드 · 배치 결정 · 배치 전략 비교 | 워크로드에 `requiredBandwidthMbps`(0 초과, 선택) 추가(22 → **23필드**). 데이터 MDC 친화도 게이트가 켜지고 그 워크로드가 요구 대역폭을 준 배치에서, 링크 대역폭이 모자란 비율만큼 링크 비용에 가산합니다 — `shortfall = clamp0to1((요구 − 링크) / 요구)`, `cost += shortfall × 1.0`. 요구가 없거나(null) 원장에 대역폭이 없으면(null) 가산 0 이라 **예전 점수 그대로**입니다. 같은 MDC 와 미등록 원격 쌍은 견줄 대역폭이 없어 영향을 받지 않습니다. 가산 눈금은 설정 노브가 아니라 코드 상수(1.0 = 지연 기준값 50ms 와 같은 눈금)라 `configDigest` 는 **`1487c10f4e97` 그대로**입니다. 링크 판에 대역폭 축(등록 두 쌍 10000·1000Mbps)과 요구 순환 배정 `[0, 500, 2000]` 을 더해 판 다이제스트 `9d04e5ef0788` → `53a71b0de49f`(판정 자격 5판은 링크 절이 없어 불변). `assumptions.workloadBandwidthAssignment` 추가 — 이 전제 필드가 늘어 **증거 묶음 20건 전부의 `entriesDigest` 가 `2d8f4ab235a9` → `ca1cffbf59e3` 로 이동**합니다(링크 판 외 18건은 전제 필드 하나만 차이이고 지표 값은 그대로). 대역폭 벌점은 코드 상수라 `configDigest` 에 실리지 않으므로, 그 상수를 바꾸는 변경은 응답 다이제스트로 드러나지 않고 **판정 기준선 재취득 사유**로 다룹니다 | 필드 추가 — 워크로드 응답 키 집합을 고정한 화면은 한 번 조정. **요구 대역폭을 주지 않은 기존 워크로드는 배치 결과·점수가 불변**입니다. 게이트를 켜고 요구를 준 배치에서만 후보 `details.링크품질` 이 내려가고 순위가 갈릴 수 있습니다 |
| #73 | 2026-09-17 | 배치 결정 | 후보 상세에 `exclusionCode` 추가 — 제외 사유를 기계가 읽는 이름 14종(`INSUFFICIENT_RESOURCE`·`INSUFFICIENT_DISK`·`CPU_USAGE_EXCEEDED`·`MEMORY_USAGE_EXCEEDED`·`COOLING_DOWN`·`TEMPERATURE_EXCEEDED`·`WORKLOAD_TEMPERATURE_EXCEEDED`·`PREDICTED_TEMPERATURE_EXCEEDED`·`POWER_HEADROOM_EXCEEDED`·`WORKLOAD_POWER_EXCEEDED`·`RUN_POWER_BUDGET_EXCEEDED`·`PREDICTED_POWER_EXCEEDED`·`MDC_LINK_DOWN`·`MDC_POWER_BUDGET_EXCEEDED`)으로 나눕니다. **기존 `exclusionReason` 문장은 한 글자도 바뀌지 않습니다** — 까닭 14종 가운데 12종의 문구를 시험으로 고정했습니다(11종은 문장 전체, MDC 전력 합계는 앞뒤). 적격 후보는 `exclusionCode` 가 없습니다(`null`). 판 응답의 온도 제약 개입 횟수(`c3InterventionCount`)가 사유 문장 부분 검색에서 코드 비교로 바뀌었고 세는 조건이 같아 값은 불변입니다(증거 묶음 `entriesDigest` `ca1cffbf59e3` 그대로) | 필드 추가 — 문장으로 분기하던 화면은 코드로 옮기는 편이 안전합니다. 문장·값·다이제스트 전부 불변이라 조정 없이도 동작합니다 |
| #74 | 2026-09-17 | 실행 이력 | 실행 상세에 `clusterSnapshot` 추가 — 실행 1회의 클러스터 상태를 열 12개로 남깁니다. 배치를 얹어도 다시 재지 않는 축(`nodeCount`·`excludedCount`·`measuredNodes`·`measuredPowerWatts`)은 한 번만, 예약에 반응하는 축(`activeNodes`·`reclaimableWatts`·`avgTemperature`)은 전·후로 싣습니다. "후"는 재측정이 아니라 이번 실행의 예약을 반영한 값입니다. **실행 이력 목록 1행(`ScheduleRunResponse`)의 15열은 이름·순서 그대로**이고 상세에만 더했습니다. 기록이 없는 실행은 `clusterSnapshot` 이 통째로 `null` 입니다 — 이 기능 이전에 쌓인 행이 그렇습니다. 재지 못한 회차는 `null` 이 아니라 까닭을 담은 `status` 를 가진 객체입니다(`NO_TARGET`·`NO_NODES`). 배치 점수·선택·판 다이제스트는 불변입니다(읽기만 하고 맥락을 고치지 않습니다) | 필드 추가 — 실행 상세만. 목록·즉시 실행 응답은 불변이라 조정 없이 동작합니다 |
| #75 | 2026-09-18 | 전력 최적화 추세 | 응답 최상위에 `energy` 추가(5 → 6열) — 구간 전체의 에너지 요약입니다. **지점 배열은 11열 그대로**입니다. 지점의 `totalPowerWatts` 는 전력을 못 읽은 노드를 0 으로 채워 만든 합이라 그 배열을 적분하면 결손이 0W 로 읽혀 적게 나오므로, 소비와 회수 가능 둘 다 0 을 채우기 전의 원시 구간 시계열을 노드별로 적분하고, 회수 가능 쪽만 가동 판정을 한 번 더 거칩니다. 물은 구간과 실제로 적분한 구간이 다를 수 있어 `integratedFrom`·`integratedTo`·`integratedHours`·`gaps` 를 함께 냅니다. 배치 점수·선택·판 다이제스트·`configDigest` 전부 불변입니다 | 필드 추가 — 추세 응답 최상위 키 하나. 지점 배열과 기존 5열은 불변이라 조정 없이 동작합니다 |
| #76 | 2026-09-21 | 노드 · 전력 최적화 진단 · 운영자 입력 원장 | 수집기가 GPU 개수·전력·온도를 노드 `instance` 로 찾던 것을 **익스포터별 `instance` 해석**으로 — 원장(신설 `/node-exporter-mappings`, 5 엔드포인트, 59 → **64**) → 포트를 뗀 호스트명 일치(정확히 하나) → 노드 `instance` 그대로 순. 전력 최적화 추세·전력 효율 추세의 노드별 전력 결합도 같은 해석기를 씁니다. 실장비의 `instance` 는 익스포터마다 달라(노드 `:9100`, DCGM `:9400`, IPMI 중앙 주소) 셋이 전부 빈손이었습니다. 노드 응답 21필드·추세 응답 형태는 그대로이고, `instance` 가 노드와 같은 저장소에서는 값도 불변입니다(모의 저장소는 이번에 익스포터별 포트를 내도록 바뀌었고 호스트명 일치로 이어져 값은 같습니다). 같은 시리즈로 해석된 노드가 둘이면 전부 미계측 — 원장은 다른 행이 쓰는 `instance` 를 409 로 막습니다. 배치 점수·판 다이제스트·`configDigest`·`entriesDigest` 불변 | 필드 변경 없음. 새 원장 5 엔드포인트 — 운영자 입력 화면 한 장 추가. 실장비에서 GPU·전력·온도가 0 에서 값으로 바뀌는 것은 데이터가 이어진 결과입니다 |
| #77 | 2026-09-21 | 노드 | 노드 응답에 `gpuUsagePercent`·`gpuTemperatureCelsius`·`gpuPowerWatts` 추가(**21 → 24필드**). 값은 **`0` = 미계측**이고 **칸마다 따로**입니다(3장 노드 응답의 GPU 축). 비는 조건은 다섯 — DCGM 타깃 down · 원장 미기입으로 호스트명 일치 불가 · 같은 시리즈 대응 충돌 · GPU 인벤토리 0 · 그 메트릭 카운터를 익스포터가 안 냄. 새 키는 `networkTxBps` 뒤에 끼워 넣었습니다(기존 키 상대 순서 그대로). 사용률은 카드 평균(`avg by(instance)`), 온도는 가장 뜨거운 카드(`max`), 전력은 카드 합(`sum`)이고 **노드 `powerWatts`·`temperatureCelsius` 에는 섞지 않습니다**(이중 계상 방지). 질의는 클러스터 한 번씩 세 건이 늘고(동시에 던져 상한은 한 번) 노드당 질의 수는 그대로입니다. 실행 상세 `clusterSnapshot` 12열 · 전력 효율(PEI·`powerSource`) · 추세 · 배치 점수 · 판 다이제스트 · `configDigest 1487c10f4e97` · `entriesDigest ca1cffbf59e3` · 엔드포인트 64 **전부 불변**, 스키마 변경 없음 | 필드 추가 — 노드 응답 키 집합을 고정한 화면은 한 번 조정. 배포 서버에서는 DCGM 이 down 이라 세 값이 0 입니다 |
| #78 | 2026-09-21 | 배치 결정 | 후보 상세에 `디스크검사` 추가 — 워크로드가 `requiredDiskGb > 0` 일 때만 **모든 후보**에 붙습니다(3장 주의 11). `1.0` 은 총량을 알아 C1 디스크 검사의 대상인 노드, `0.0` 은 총량을 몰라 검사 없이 통과시킨 노드이며 적격·부적격과 무관합니다(부족은 `exclusionCode = INSUFFICIENT_DISK`). 디스크 요구가 없는 워크로드의 `details` 키 집합·배치 점수·선택·배치 이력(`details` 미저장)·통합 권고·판 다이제스트·`configDigest 1487c10f4e97`·`entriesDigest ca1cffbf59e3`(판 워크로드는 디스크 요구 0) 전부 불변, 스키마 변경 없음. 총량 모름 노드가 적격에 있는 배치는 서버 로그에 WARN 한 줄(실행 루프·단건·모의 배치 모두) | 필드 추가 — 디스크를 요구하는 배치의 후보 상세 키 하나. `0.0` 은 "검사하지 않았다" 이지 "부족하다" 가 아닙니다. 요구가 없는 배치는 응답이 바이트 단위로 불변이라 조정 없이 동작합니다 |
| #80 | 2026-09-21 | 배치 결정 · 배치 전략 비교 · 통합 권고 | 한계 전력 `PowerModel.realMarginalPower` 의 **유휴 기준선을 한 값으로** — 동적 범위 `(P_max − 기준선)` 의 기준선을 깨우기항과 같은 `wakeupPower`(계측된 유휴 노드는 실측 역산, 가동·미계측·정격 미상·역산 범위 밖·역산 끔은 대체값 = 선언 유휴)로 맞췄습니다. 새 노브 없음. 진단 API 의 유휴 전력·비례성·비낭비·볼록 곡선 절편, 공개 `marginalPowerOnCurve`, 선형 `marginalPower`(제약 C2·배치 후 예상 전력·대조군 pabfd)는 선언 유휴 그대로 — 지목 밖이며, 그래서 계측 유휴 노드에서는 한계 전력 점수의 절편과 제약·예상 전력의 절편이 갈립니다(다음 회차 후보). **전력을 못 읽는 노드(IPMI 없음)·정격 미상·역산 범위 밖은 비트 단위로 불변**입니다. 움직이는 곳: 후보 상세 `실질한계전력W`·`실질한계전력점수` 와 그것이 든 `전력점수`·총점·정렬(계측 유휴 노드가 후보에 있을 때), 통합 권고 `moves[].marginalWatts` 와 그 합인 이동 비용·순절감 — 반납분 `idlePowerWatts` 는 이미 역산이라 불변이고 비용 쪽이 같은 기준선으로 따라오며, 계측 유휴 노드로 옮기는 후보에서 `NET_SAVING` ↔ 손해 차단 판정과 문구가 뒤집힐 수 있습니다(비율 `powerRatioToIdle` 분모는 가정값 그대로). 비교 판은 판 파일 기반이라 실클러스터와 무관하게 전력 엔진 열의 값·선택(`engineBelievedCostW` 포함)이 움직이며 판정 자격 2판 `power` 행 판정 셀 12.4476 → **11.8396** · 방향 셀 16.1995 → **16.6866**(판정 결말 `FAIL` — 2항, 8장 판정 이력). 다음 판정의 출발선(이 리비전 11.8396 / 16.6866)은 다음 회차 앞 재취득 PR 이 기준선 파일로 확정합니다. 판 다이제스트·워크로드 다이제스트·규칙 축 8값·`rulesDigest`·`configDigest 1487c10f4e97`·엔드포인트 64 불변, 증거 묶음 `entriesDigest ca1cffbf59e3` → `0cafa69fbcc9`, 스키마 변경 없음 | 필드·형태·다이제스트 변경 없음. 값 변경 — 통합 권고 화면·배치 이력이 연동 대상이고, 비교 판 응답은 화면 비연동(연구 검증용)입니다. **배포 서버는 IPMI 가 없어 실클러스터 기반 응답(노드·진단·배치 결정·통합 권고)이 바이트 단위로 불변**이고 판 파일 기반 비교 응답만 움직입니다 — 배포에서는 이 정정의 효과를 관측할 수 없으며 불변이 곧 무효를 뜻하지 않습니다 |
| #81 | 2026-09-22 | 익스포터 대응 | **후보 `instance` 발견 API** `GET /node-exporter-mappings/candidates` 신설(엔드포인트 **64 → 65**). 저장소에 지금 있는 DCGM·IPMI 시리즈의 `instance` 와 수집기의 배정 근거(`assignedBy` 6값)·충돌 당사자·원장 노드·원장에만 있는 값·답하지 않은 질의 이름을 냅니다. 답하지 않은 질의가 뒷받침하는 칸은 서버가 비워 둡니다(6장). 이전에는 화면이 저장소에서 `count by(instance)` 를 직접 확인해 입력해야 했습니다(6장). 기존 64 엔드포인트·응답·`configDigest 1487c10f4e97`·판 다이제스트·`entriesDigest` 전부 불변 — 엔진·비교 판·설정을 건드리지 않습니다 | 엔드포인트 추가. 기존 화면 영향 없음. 배포 서버는 DCGM·IPMI 타깃이 down 이라 두 목록이 비고 `unavailable` 도 비어 있는 것이 옳은 답입니다(9장) |
| #82 | 2026-09-22 | 배치 이력 | 이력 응답에 **`exclusionCodes`** 추가(**22 → 23필드**) — 후보 노드의 제외 사유 코드를 코드별로 센 JSON 객체 문자열(적격 후보 미포함 · 제외 없으면 `null` · 옛 행 `null`). 엔진이 돌려주던 후보별 `exclusionCode` 를 이력이 버리고 있었고, 그동안은 사유 문장(`reason`) 하나만 남아 코드 기준 집계가 불가능했습니다(이력 API 의 필터·정렬 항목은 늘지 않았습니다 — 코드별 집계는 화면 파싱 또는 DB 질의로 합니다). 모집단은 `candidateCount`, 한 노드는 먼저 걸린 제약 하나로만 셈(3장 주의 4). 스키마: `scheduling_history.exclusion_codes` TEXT 열 1개(`deploy/sql/2026-09-22_scheduling_history_exclusion_codes.sql`, 평문 — 열거형 CHECK 를 두지 않아 코드가 늘어도 저장이 깨지지 않음). 엔진·비교 판·설정 무접촉 — `configDigest 1487c10f4e97`·판 다이제스트·`entriesDigest`·배치 결과 불변, 엔드포인트 65 불변 | 필드 추가(새 키는 맨 끝). 키 집합을 고정한 화면은 한 번 조정. 값은 문자열이라 파싱해서 사용. 표에 없는 코드 키가 올 수 있음 |
| #83 | 2026-09-22 | 실행 이력 상세 | 실행 상세의 결정 목록(`decisions[]`)에 **`exclusionCodes`** 추가(**12 → 13필드**, 맨 끝) — 이력 응답과 같은 JSON 객체 문자열(코드별 건수 · 적격 후보 미포함 · 제외 없거나 옛 행이면 `null`). 그동안 실행 상세의 실패 결정은 엔진 총괄 문장(`reason`) 한 줄뿐이었고, 같은 값이 이력 행에 있어도 이력 조회에는 실행 기준 필터(`scheduleRunId`)가 없어 실행 단위로 모아 볼 수 없었습니다. 실행 이력 목록·즉시 실행 응답·엔진·비교 판·스키마·엔드포인트 65 전부 불변 | 필드 추가(새 키는 맨 끝). 결정 목록의 키 집합을 고정한 화면은 한 번 조정. 값은 문자열이라 파싱해서 사용(3장) |
| #84 | 2026-09-22 | 배치 이력 | 페이징 조회(`/history/page`)에 **`scheduleRunId` 필터** 추가 — 한 스케줄 실행이 만든 배치 결정을 이력 API 에서 실행 단위로 모아 봅니다. 실행 상세 `decisions[]` 와 같은 모집단이라 두 응답의 총건수가 같습니다(정렬·페이지 크기 차이와 실행 id 가 비는 행의 규칙은 3장 **실행 필터** 블록). 필터 4종을 3장 주의 5 에 함께 기재했습니다. 열 `schedule_run_id` 와 인덱스는 기존이라 스키마 변경 없음. 엔진·비교 판·설정 무접촉 — `configDigest 1487c10f4e97`·판 다이제스트·`entriesDigest 0cafa69fbcc9`·엔드포인트 65 불변 | 필드·형태 변경 없음. 질의 파라미터 하나 추가 — 쓰지 않으면 응답 불변 |
| #85 | 2026-09-28 | 배치 이력 | 페이징 조회(`/history/page`)에 **`exclusionCode` 필터** 추가 — 그 코드로 센 후보가 하나 이상 있는 결정만 서버에서 걸러 총건수(결정 수)·페이지를 코드 기준으로 얻습니다(#82 가 "화면 파싱 또는 DB 질의로" 남긴 것 중 행 고르기 — 코드별 합계는 여전히 화면이 `exclusionCodes` 를 더합니다). 키 단위 매칭 · `null` 행 미해당 · 빈 값은 필터 없음 · 소문자·모르는 코드 400 · 부분 일치라 전수 검색(3장 **`exclusionCodes`** 의 **필터** 행). 응답 필드·형태·엔드포인트 65·스키마 변경 없음. 엔진·비교 판·설정 무접촉 — `configDigest 1487c10f4e97`·판 다이제스트·`entriesDigest 0cafa69fbcc9` 불변 | 필드·형태 변경 없음. 질의 파라미터 하나 추가 — 쓰지 않으면 응답 불변 |
| #86 | 2026-09-28 | 배치 이력 | **`GET /history/exclusion-summary`** 신설(엔드포인트 **65 → 66**) — 창·필터 안 결정의 제외 사유 코드 집계를 서버가 더해 코드별 결정 수·후보 수를 돌려줍니다(#82 가 "화면 파싱 또는 DB 질의로", #85 가 "코드별 합계는 여전히 화면이 더합니다" 로 남긴 공백). 페이징과 같은 조건식이라 응답의 창을 넘긴 같은 필터의 `exclusionCode=CODE` 총건수가 `codes[CODE].decisions` 와 같습니다(`skipped` 0 일 때). 함께 `/history/page` 에 **`from`·`to` 창**(`[from, to)`, ISO-8601, 기본 창 없음)을 추가했습니다. 합계도 같은 창을 받되 둘 다 비우면 최근 30일이고, 응답이 실제 창을 돌려줍니다. 형식이 어긋난 집계 행은 건너뛰어 `skipped` 로 셉니다(3장 **`exclusionCodes`** 의 **합계** 행). 기존 응답 필드·형태·스키마 변경 없음. 엔진·비교 판·설정 무접촉 — `configDigest 1487c10f4e97`·판 다이제스트·`entriesDigest 0cafa69fbcc9` 불변 | 엔드포인트 추가 · 페이징에 질의 파라미터 둘 추가 — 쓰지 않으면 기존 응답 불변 |
| #88 | 2026-09-28 | 노드 | 노드 응답에 **디스크 축 3칸** `diskCapacityGb`·`reservedDiskGb`·`availableDiskGb` 추가(**24 → 27필드**, GB 정수). C1 디스크 검사와 같은 식의 총량·예약·가용이며 `diskCapacityGb = 0` 은 모름(그 노드는 디스크 때문에는 빠지지 않고 `availableDiskGb` 도 0). CPU·메모리·GPU 와 같이 총량·예약·가용 축의 제자리에 넣었습니다(3장 「노드 응답의 디스크 축」). | 필드 추가 — 노드 응답 키 집합을 고정한 화면은 한 번 조정 · 기존 24키의 값·상대 순서 불변 · 새 키 셋은 세 자리(`totalGpu` 뒤 · `reservedGpu` 뒤 · 맨 끝)에 흩어짐 · 엔드포인트 66·이력 응답·엔진·판 불변 |
| #89 | 2026-09-28 | 실행 이력 | 실행 이력 목록(`/schedules/{id}/runs`)에 **`status`·`triggerType`·`from`·`to` 필터** 추가 — 처리 상태·실행 주체·시작 시각 창(`[from, to)`, 기본 창 없음)으로 서버가 걸러 총건수를 돌려줍니다(2장 주의 9). 지금까지는 정렬·페이징만 받아 화면이 전 페이지를 받아 걸러야 했습니다. | 질의 파라미터 넷 추가 — 쓰지 않으면 기존 응답 불변 · 응답 형태·필드·엔드포인트 66 불변 · 열거값 밖의 `status`·`triggerType` 은 이제 400 |
| #90 | 2026-09-28 | 워크로드 | **`GET /workloads/page`** 신설(엔드포인트 **66 → 67**) — 워크로드 목록의 페이징·총건수·필터(`status` 다중값 OR · `resourceType` · `keyword` · `assignedNodeId`)·정렬(허용 필드 11개 · `priority` 제외)과 `id` 보조 정렬, 행은 `/workloads` 와 같은 23필드(3장 주의 12). 기존 `/workloads` 는 배열로 상한 없이 그대로이고 워크로드는 자동 정리가 없어 표 화면은 새 경로를 씁니다. | 엔드포인트 추가 · 기존 `/workloads` 응답 형태·필드 불변(Swagger 설명만 추가) · 엔진·판·설정 무접촉 |
| #91 | 2026-09-29 | 워크로드 | **`GET /workloads/retry-policy`** 신설(엔드포인트 **67 → 68**) — 재시도 상한 `maxRetryCount`·간격 `retryBackoffMinutes` 를 설정에서 읽어 줍니다(지금까지 상한은 계약 어디에도 없었고 간격은 문서에 15분으로 박혀 있었습니다). `/workloads/page` 에 **`retryExhausted` 필터** 추가 — 소진(`FAILED` 이고 `retryCount ≥ 상한`) 행만 또는 그 나머지(3장 주의 6·7·12). 단건 배치가 소진·간격을 보지 않는다는 사실을 기재했습니다. | 엔드포인트 추가 · 질의 파라미터 하나 추가 — 쓰지 않으면 기존 응답 불변 · 응답 형태·필드 불변 · 엔진·판·설정 무접촉(설정은 읽기만) |
| #92 | 2026-09-29 | 스케줄 · 실행 이력 · 배치 이력 · 전달 기록 | 페이징 네 곳에 **`id` 보조 정렬** 적용(1.1절 **보조 정렬**) — 값이 같은 행의 순서를 DB 에 맡겨 `status`·`success`·`enabled` 처럼 동률이 많은 필드로 정렬하면 페이지 경계에서 행이 겹치거나 빠질 수 있었습니다(#90 은 워크로드 페이징에만 적용했고, 나머지 네 곳의 결함은 #84·#85·#90 이 3장 「정렬·크기」·「필터」와 1.1절에 공개 기재해 두었던 것). 실행 상세 `decisions[]` 도 같은 시각이면 `id` 오름차순으로 고정해 배치 이력 페이징 `sort=createdAt,asc` 와 행 순서가 같습니다. | 응답·파라미터·허용 정렬 필드·엔드포인트 68 불변 · 동률 행의 순서만 확정(전에는 DB 임의 — 소규모 표에서 삽입순으로 보이던 화면은 내림차순 정렬에서 같은 값 안이 최신 먼저로 바뀜) · 엔진·판·설정 무접촉 |
| #93 | 2026-09-29 | 워크로드 | `/workloads/page` 에 **`priority` 필터** 추가 — `LOW`·`NORMAL`·`HIGH`·`CRITICAL` 을 `status` 와 같은 모양(반복·쉼표 다중값 OR · 대소문자 무시 · 모르는 값 400)으로 받습니다(3장 주의 12). 정렬은 여전히 허용하지 않습니다(1.1절). | 질의 파라미터 하나 추가 — 쓰지 않으면 기존 응답 불변 · 응답 형태·필드·허용 정렬 필드·엔드포인트 68 불변 · DDL 없음 · 엔진·판·설정 무접촉 · 배포 뒤부터 유효(옛 서버는 이 파라미터를 무시하고 전체를 돌려줌) |
| #95 | 2026-09-29 | 워크로드 | `/workloads/page` 의 `sort` 에 **`priority`** 허용 — 알파벳순이 아니라 등급 순(LOW → CRITICAL)이고 같은 등급은 `id` 보조 정렬(1.1절). 허용 정렬 필드 11 → 12. | 응답 형태·필드·파라미터·엔드포인트 68 불변 · `sort=priority` 가 400 → 200 · DDL 없음 · 엔진·판·설정 무접촉 · 배포 뒤부터 유효(옛 서버는 400) |
| #96 | 2026-09-29 | 스케줄러 계약 | LIKE 이스케이프 공용화(이력 `exclusionCode`·워크로드 `keyword` 질의 결과 불변) · 변경 이력 7행에 PR 번호 · 기간 규칙을 1.3절 표로(추세 두 API 는 시작이 끝과 **같아도** 400 — 문서 정정) · 시간대 응답 위치(`/cron/validate`) · 일괄 재개 상한이 설정값 · 정책 선택 순서(5장) · 원장 목록 필터(6장) · MDC 링크 태그의 대역폭 문안 | 응답·질의 결과·엔드포인트 68 불변 · DDL 없음 · 엔진·판·설정 무접촉 · 동작 변경 없음(코드 정리 · 문서·Swagger 태그 문안 정정) |
| #97 | 2026-09-30 | 스케줄 | 목록 `keyword` 의 `%`·`_` 를 문자 그대로 찾음(전에는 와일드카드 — `a_b` 가 `axb` 에도 맞음) · `resourceType=VM` 이 종류를 비워 둔 스케줄 포함(전에는 제외 — 실행은 VM 으로 돌리는데 목록에서는 빠짐)(2장 주의 10) | 응답 형태·파라미터·허용 정렬 필드·엔드포인트 68 불변 · 두 필터의 결과 집합만 바뀜(`%`·`_` 가 든 검색어는 결과가 같거나 줄고, `resourceType=VM` 은 같거나 늚) · DDL 없음 · 엔진·판·설정 무접촉 · 배포 뒤부터 유효(옛 서버는 `%`·`_` 를 와일드카드로 읽고 VM 필터에서 종류 빈 스케줄을 뺌) |
| #98 | 2026-09-30 | 배치 결정 | 단건 배치·모의 배치 응답에 **`policyName`** 추가 — 적용된 배치 정책 이름(5장 선택 순서 ②~④ · 성공·실패·모의 배치·실행 계층 거부 결과 모두)(3장 주의 3). 지금까지 단건 배치의 실패·모의 배치는 적용 정책이 이력에도 응답에도 없었습니다(스케줄 실행의 실패 결정은 이력에 있음). | 키 하나 추가(맨 끝) — 키 집합을 고정한 화면은 한 번 조정 · 기존 여섯 키의 값·엔드포인트 68 불변 · DDL 없음 · 엔진·판·설정 무접촉 · 옛 서버 응답에는 없음 |
| #99 | 2026-09-30 | 스케줄 | **실행 미리보기 `GET /schedules/{id}/preview`** 신설(2장 주의 11) — 지금 실행하면 적용될 정책·엔진과 고른 단계, 대상·재시도 대기·재시도 소진 건수, 대상 0건일 때의 사유와 실행이 남길 문구를 저장 없이 돌려줍니다. 전에는 정책 2회 호출 + 서버 선택 규칙 복제, 건수는 페이징 4회 호출로 조합해야 했고 실행 전 사유는 알 수 없었습니다. | 엔드포인트 하나 추가(68 → 69) · 기존 응답·파라미터·DDL 불변 · 엔진·판·설정 무접촉 · 실행 동작 불변(정책이 없을 때 `default` 저장은 실행에서만 · 미리보기는 저장 없음) · 배포 뒤부터 유효(옛 서버는 매핑이 없어 403 · 본문 없음 — "스케줄 없음" 의 404 와 구분) |
| #100 | 2026-09-30 | 워크로드 · 전달 기록 | **배치 예약 취소 `POST /workloads/{id}/release`** 신설(3장 주의 13 · 7장) — 배치된 워크로드를 대기로 되돌리고 배정을 풀며 현재 결정의 전달 기록을 `FAILED` 로 마감합니다(비고 "예약 취소"). 전에는 상태 변경 API 의 `SCHEDULED → FAILED → PENDING` 조합밖에 없어 배정 컬럼이 남고 재시도 횟수가 지워지며 전달 기록이 `ACCEPTED` 로 방치됐습니다. | 엔드포인트 하나 추가(69 → 70) · 기존 응답·상태 전이표·전달 기록 상태 값·DDL 불변 · 엔진·판·설정 무접촉 · 상태 변경 API 의 `SCHEDULED → PENDING` 은 여전히 400 · 전달 기록 `status=FAILED` 에 예약 취소가 포함됨(실행 실패만 세려면 `message` 표지로 구별 · 서버 필터 없음) · 워크로드 `assignedNodeId` 가 비는 경우 하나 추가(3장 주의 12) · 삭제 409 문구가 `SCHEDULED` 에서는 예약 취소를 안내 · 배포 뒤부터 유효(옛 서버는 매핑이 없어 403) |
| #102 | 2026-10-01 | 워크로드 | **워크로드 편집 `PUT /workloads/{id}`** 신설(3장 주의 14 · 주의 9 정정 · 주의 12 보강) — 보낸 키만 바꾸는 부분 갱신이고 여섯 칸은 `null` 로 비울 수 있습니다. 전에는 생성·상태 변경·삭제만 있어 요구 대역폭 같은 값을 지울 길이 없었고, 요구량을 고치려면 지우고 다시 만들어야 해 id·이력·전달 기록 연결과 대기 순번이 끊겼습니다. | 엔드포인트 하나 추가(70 → 71 · Swagger 경로 수 불변 — 기존 `/workloads/{id}` 에 메서드 추가) · 기존 응답 23필드·상태 전이표·재시도 회계·DDL 불변 · 엔진·판·설정 무접촉 · 배포 뒤부터 유효(옛 서버는 매핑이 없어 403) |
| #104 | 2026-10-06 | 스케줄 | **저장 전 미리보기 `POST /schedules/preview`** 신설(2장 주의 12) — 생성 폼의 값으로 실행 미리보기(주의 11)와 같은 정책·엔진·건수를 저장 없이 돌려줍니다. 전에는 저장된 스케줄만 미리 볼 수 있어 폼 값을 확인하려면 만들고 보고 지워야 했고, 그러면 행과 id 가 남고 자동 실행이 켜진 폼은 실제 실행으로 이어질 수 있었습니다. | 엔드포인트 하나 추가(71 → 72 · Swagger 경로 333 → 334) · 요청 스키마는 생성 요청 재사용 · 응답은 기존 미리보기 14필드 그대로 · 저장된 스케줄 미리보기·생성·편집 동작과 DDL 불변 · 엔진·판·설정 무접촉 · 배포 뒤부터 유효(옛 서버는 메서드가 없어 403) |
| #106 | 2026-10-06 | 스케줄 | **편집 지정 해제** — 편집 `PUT /schedules/{id}` 의 본문이 편집 요청(`ScheduleUpdateRequest`)으로 바뀌고 정책·엔진·전력 상한·온도 상한·설명 다섯 칸을 `null` 로 비울 수 있습니다(2장 주의 13 · 주의 3·12 정정). 전에는 편집이 `null` 을 유지로만 읽어 지정 값을 비울 길이 없어, 참조 스케줄이 있는 정책을 지우려면(409) 스케줄의 정책을 바꾸거나 스케줄을 지워야 했습니다. 이제 `policyId` 를 `null` 로 보내 참조를 풀 수 있습니다(참조가 남아 있으면 삭제는 지금도 409). | 엔드포인트·경로 수 불변(요청 스키마 하나 추가) · 생성·저장 전 미리보기는 생성 요청 그대로 · 응답·DDL 불변 · 엔진·판·설정 무접촉 · **의미 변화**: 다섯 칸에 `null` 을 보내면 이제 비워지므로 전체 본문을 `null` 로 채워 보내던 화면은 키를 빼거나 조회 값을 넣어야 함 · 옛 서버는 `null` 을 유지로 읽음 |
| #107 | 2026-10-06 | 벤치마크 | **기준선 재취득과 판정 이력** — 회차 3 지목을 소비 목록에 내리고 다음 판정 주어를 공석으로 두는 `rules:` 커밋(judgment `3bd73f1c4650` · prose `75a82028ba09`)과 같은 PR 에서 기준선 `8696f4da2e4d` 을 다시 떴습니다(8장). 판정 게시 없음. | 엔드포인트·경로·스키마·응답·DDL 불변 · 엔진·판·설정 무접촉(설정 다이제스트 `1487c10f4e97` · 판 다이제스트 · 두 셀 중앙값 11.8396 / 16.6866 불변) · 바뀌는 것은 규칙 문장(`GAP_JUDGMENT_RULE` 의 소비 목록·`JUDGMENT_TARGET`)과 시험 자원 파일뿐 — 화면 영향 없음 |
| #108 | 2026-10-06 | 배치 결정 · 배치 전략 비교 | **열관성 계수 음수 보정** — `aims.scheduler.thermal.inertia-k` 가 음수면 0 으로 적용하고 기동 때 WARN 1줄(원시값 포함). 전에는 음수가 배치 후 예상 온도·예약 노드 실효 온도를 실측보다 낮춰 배치할수록 식는 예측이 되어 온도 게이트가 느슨해졌습니다. NaN·무한대는 다루지 않습니다(설정 검증 일반 과제). | 필드·형태 불변. 기본 설정(0.15)에서 응답·값·설정 다이제스트 `1487c10f4e97` 불변(배포 서버는 yml 에 이 키가 없어 기본값). 음수를 둔 배포에서는 `engineConfig.thermal.inertiaK` 와 설정 다이제스트가 **적용값 0** 을 보입니다 — PEI 정규화 상수가 원시값을 싣고 쓰는 시점에 보정하는 것(#64)과 다르며, `unknownLinkScore` 가 유한하지 않은 값을 기본값으로 보는 것과도 NaN 처리가 다릅니다(설정 검증 과제에서 통일) |
| #109 | 2026-10-06 | 배치 결정(단건 배치 · 모의 배치) | **종류 없는 워크로드의 정책 선택** — 종류(`resourceType`)가 없으면 VM 으로 읽어 VM 활성 정책을 고릅니다(5장 선택 순서 ②). 전에는 ②를 건너뛰고 공통 정책으로 가서, 같은 워크로드가 스케줄 실행과 다른 정책으로 판정될 수 있었습니다(3장 주의 3 정정 · 5장 ② 보충). | 필드·형태 불변. VM 활성 정책이 있는 배포에서만 종류 없는 워크로드의 `policyName` 과 결정이 공통(또는 `default`) → VM 정책으로 바뀔 수 있음(배포 서버는 VM 정책이 없어 종전과 같음). 저장값 `resourceType` 은 `null` 유지 · 벤치마크·설정 다이제스트 무영향 |
| #110 | 2026-10-07 | 워크로드 · 배치 결정 | **저장 전 모의 배치 `POST /workloads/dry-run`** 신설(3장 주의 15) — 생성 폼 값으로 단건 모의 배치와 같은 일곱 키를 저장 없이 돌려줍니다. 전에는 저장된 워크로드만 모의 배치할 수 있어 폼 값을 확인하려면 만들고 보고 지워야 했고, 그 사이 대기 행이 목록과 스케줄 실행에 노출됐습니다. | 엔드포인트 +1(72 → 73) · 요청은 생성 스키마 그대로 · 응답은 단건 모의 배치와 같은 모델(스키마 목록에 결과·후보 점수 스키마가 새로 실림) · 기존 모의 배치·생성·편집 동작과 DDL 불변 · 엔진·판·설정 무접촉 · 옛 서버는 403 |
| #111 | 2026-10-07 | 스케줄 실행 | **무산된 배치의 예약 되돌리기** — 실행 계층이 받지 않은 배치(`REJECTED`·`UNAVAILABLE`)의 예약을 같은 실행 안에서 되돌립니다. 전에는 그 예약이 실행이 끝날 때까지 남아, 같은 실행의 다음 워크로드가 없는 예약 때문에 자원 부족으로 실패하고 실행 후 스냅샷에도 그 예약이 들어갔습니다(2장 실행 전후 클러스터 상태 설명 정정). | 필드·형태·엔드포인트 불변. 거부·불가가 나오지 않는 record 모드 배포에서는 동작 차이 없음 |
| #112 | 2026-10-07 | 스케줄 | **모의 실행 `POST /{id}/dry-run`** 신설(2장 주의 14) — 지금 실행하면 어떻게 배치될지를 실행과 같은 정책·엔진·대상·순서·상한·전력 상한·온도 상한으로 노드 상태 하나 위에 차례로 놓아 보고 저장 없이 돌려줍니다. 전에는 실행 미리보기가 시도할 건수만 주고 워크로드별 모의 배치는 스케줄이 지정한 정책·온도 상한·전력 상한과 앞 건의 예약을 반영하지 못해, 실행해 보기 전에는 결과를 알 수 없었습니다(주의 11 안내 정정). | 엔드포인트 +1(73 → 74) · 스키마 +3(`ApiResponseScheduleDryRunResponse`·`ScheduleDryRunResponse`·`DryRunDecision` · `ClusterSnapshot` 재사용). 기존 경로·응답·DDL 불변 · 실행 경로는 결과 문구 함수 추출만 |
| #113 | 2026-10-07 | 스케줄 | **저장 전 모의 실행 `POST /dry-run`** 신설(2장 주의 15) — 생성 폼 값으로 주의 14 와 같은 모의 실행을 저장 없이 돌려줍니다. 전에는 폼 단계에서 시도할 건수(저장 전 미리보기)만 볼 수 있었고 노드 위에 놓아 본 결과는 저장한 뒤에만 볼 수 있었습니다. 온도 상한 칸이 있으면 400 입니다. | 엔드포인트 +1(74 → 75) · 요청은 생성 스키마, 응답은 모의 실행 스키마 재사용(스키마 +0) · 기존 경로·응답·DDL 불변 |
| #114 | 2026-10-08 | 워크로드 · 노드 · 배치 결정 · 전력 최적화 진단 | **응답 타입 명시**(1.4절) — 작업 10개의 200 응답 스키마가 임의 객체에서 실제 응답 타입으로 바뀌고, 리소스 종류·엔진·냉각 대기의 원소와 일괄 재개 `data` 의 키 순서가 선언 순서로 고정됩니다(1장 「키 순서」). 응답의 키 집합·값은 그대로입니다. | 엔드포인트·경로·응답 값 불변 · 스키마 +28(513 → 541) · 기존 스키마의 이름·내용 불변 · 작업 10개의 200 스키마 이전 → 이후는 1.4절 표 · DDL 없음 · 엔진·비교 판·설정 무접촉 |
| #116 | 2026-10-08 | 노드 · 전력 최적화 진단 | **중첩 스키마 이름 지정**(1.4절) — 전력 효율·전력 최적화·통합 진단 응답 안의 중첩 스키마 다섯이 응답 이름을 붙인 이름으로 바뀝니다(`NodeEfficiency` → `PowerEfficiencyNodeEfficiency` · `Mdc`·`Cluster`·`Finding` → `PowerOptimizationMdc`·`PowerOptimizationCluster`·`PowerOptimizationFinding` · `Move` → `ConsolidationMove`). 전에는 단순 이름이라 다른 응답이 같은 이름을 공개하면 Swagger 에 한쪽 내용만 남을 수 있었습니다. | 응답 JSON·엔드포인트·경로 불변 · 스키마 수 541 그대로(이름 5 교체 · 내용 동일) · 이 다섯을 가리키는 `PowerEfficiencyResponse`·`PowerOptimizationResponse`·`ConsolidationCandidate` 는 참조 이름만 바뀜 · Swagger 생성 타입은 다섯 타입 이름이 바뀜(필드 동일) · DDL 없음 · 엔진·비교 판·설정 무접촉 |
| #123 | 2026-10-12 | 배치 전략 비교 | **문서 정정** — 8장의 응답 다이제스트 필드 이름을 실제 응답과 맞춥니다: `board.digest` → `boards[].digest`, `engineConfig.digest` → `engineConfig.configDigest`. 값·형태·코드 변경 없음 | 변경 없음. 화면 영향 없음 |
| #128 | 2026-10-13 | 전력 최적화 진단 | **통합 배치 권고 조회의 정책 저장 제거** — `GET /optimization/consolidation` 이 공통 활성 정책과 `default` 정책이 모두 없을 때 `default` 정책 행을 만들어 저장하던 것을 없앱니다(5장 정책 선택 ④). 전에는 읽기 조회 한 번으로 공통 활성 정책 `default` 가 생겨, 그 뒤 `default` 라는 이름의 정책 생성과 종류를 비운 정책의 `active:true` 생성·수정이 409 가 되고, `active` 를 생략한 새 공통 정책은 비활성으로 만들어졌습니다. | 응답·엔드포인트·DDL 불변. 공통 활성 정책이나 `default` 정책이 있으면 동작 차이 없음. 둘 다 없을 때 정책 행이 생기지 않는 것만 바뀜 |
| #142 | 2026-10-16 | 워크로드 · 전달 기록 | **상태 변경이 전달 기록을 마감** — `PATCH /workloads/{id}/status` 로 `COMPLETED`·`FAILED` 로 보내면 최신 결정의 열린 전달 기록을 같은 끝 상태로 마감합니다(7장 · 3장 주의 13 정정). 전에는 워크로드만 바뀌고 기록이 `ACCEPTED` 로 그대로 남아, 기록형 디스패처 모드에서 이 API 로 끝낸 워크로드의 기록이 열린 채 쌓였고, 대기로 되살려 다시 배치한 뒤 그 옛 기록에 늦게 온 콜백이 새 결정의 워크로드를 옮길 수 있었습니다. | 엔드포인트·응답·상태 값·DDL 불변. 상태 변경 응답은 그대로(워크로드만). `GET /dispatches` 의 `status=FAILED`·`COMPLETED` 에 운영자 상태 변경분이 포함됨(`message` 표지로 구별 · 서버 필터 없음). 마감된 기록의 늦은 콜백은 같은 상태면 200 무변경, 다르면 409 · 배포 뒤부터 유효(옛 서버는 기록이 `ACCEPTED` 로 남음 · 배포 전에 남은 기록은 소급 마감하지 않음) |
| #146 | 2026-10-19 | 워크로드 · 전달 기록 | **재시작 뒤 기동 정리가 전달 기록을 봄** — 스케줄 실행 도중 서버가 다시 시작되면 그 실행이 배치한 워크로드 가운데 실행 계층이 이미 받은 것(기록 `ACCEPTED`·`RUNNING`)은 그대로 두고, 전달 중이던 것(`REQUESTED`)은 기록을 `UNAVAILABLE` 로 닫고 실패로 돌립니다(7장). 전에는 전달 기록을 보지 않고 모두 실패로 돌려 받은 워크로드가 다시 배치되고 옛 기록이 열린 채 남았습니다. | 엔드포인트·응답·상태 값·DDL 불변. 바뀌는 경우는 실행 도중 재시작 뒤의 기동 정리 하나. 실행 이력은 지금처럼 실패로 마무리되므로 이력은 실패인데 배치는 남아 있는 조합이 새로 생김. `GET /dispatches?status=UNAVAILABLE` 에 재시작으로 닫힌 기록이 포함됨(`message` 표지로 구별). 배포 뒤부터 유효(옛 서버는 재시작 때 모두 실패로 돌림 · 회수 범위 밖에 배포 전부터 남은 `REQUESTED` 기록은 소급 정리하지 않음 · 배포 재시작이 끊은 실행의 기록은 새 기동 정리가 처리) |
| #147 | 2026-10-19 | 전달 기록 · 콜백 | **옛 결정의 기록에 온 콜백은 워크로드를 바꾸지 않음** — 결과 콜백이 가리키는 기록이 워크로드의 현재 결정이 아니면(요청 시각이 배정 시각보다 앞섬) 기록만 전이표대로 갱신하고 `message` 뒤에 표지를 붙여 200 으로 답하며 워크로드·재시도 횟수·배정은 그대로 둡니다(7장). 전에는 옛 결정의 늦은 콜백이 현재 결정의 워크로드를 옮기고 재시도 횟수를 올렸습니다. | 엔드포인트·응답 형태·응답 코드 집합·상태 값·DDL 불변. 바뀌는 경우는 옛 결정의 열린 기록에 온 콜백 하나(200 은 그대로 · 워크로드만 안 바뀜). Swagger 설명 한 줄 갱신. 배포 전부터 남은 옛 열린 기록은 소급 정리하지 않음 |
| #148 | 2026-10-19 | 스케줄 | **편집이 실행 상태를 덮어쓰지 않음** — 편집 저장이 바뀐 칸만 쓰므로 실행과 겹쳐도 최근 실행 네 칸(실행 상태·시작·종료·메시지)은 실행 쪽 값이 남습니다(2장 주의 13). 전에는 편집이 읽은 시점 값으로 되돌려 실행 권한이 풀리거나 끝난 실행이 실행 중으로 남을 수 있었습니다. | 엔드포인트·요청·응답·응답 코드·DDL 불변. 바뀌는 것은 편집이 실행이나 다른 편집과 겹친 순간의 결과뿐. 편집끼리 겹치면 서로 다른 칸은 둘 다 남음(같은 칸은 나중 저장). 편집 응답의 네 칸은 편집 시점 값(상세 조회로 확인) |
| #149 | 2026-10-22 | 스케줄 | **기다린 자동 실행이 시작 직전 값으로 돎** — 자동 실행이 차례가 와서 시작하기 직전에 스케줄을 다시 읽습니다. 기다리는 사이 끄거나 지운 스케줄은 그 회차를 건너뛰고(이력·최근 실행·다음 실행 시각 무변경), 바꾼 종류·순서·정책·엔진·상한은 그 회차부터 적용됩니다(2장 주의 13). 전에는 예정 시각 확인 시점의 값으로 돌았습니다. | 엔드포인트·요청·응답·응답 코드·DDL 불변. 바뀌는 경우는 예정 시각 확인 뒤 시작 전에 끄거나 지우거나 설정을 바꾼 자동 실행 하나. 즉시 실행 무변경 |
| #150 | 2026-10-22 | 스케줄 실행 · 워크로드 | **배치 직전 워크로드 다시 읽기** — 스케줄 실행이 워크로드마다 배치 직전에 다시 읽어, 대상 조회 뒤의 편집·되살리기가 배치에 반영되고 남습니다. 그 사이 지웠거나 `PENDING`·`FAILED` 가 아닌 상태로 바뀐 워크로드는 그 회차에서 건너뛰고 실패로 셉니다(실행 상세 결정 목록과 배치 이력에 사유 한 건 · 워크로드는 바꾸지 않음 · 3장 주의 14). 배치 저장이 실패한 건의 예약이 실행 후 스냅샷에 남지 않습니다. 전에는 실행 시작 때 읽은 값으로 저장해 편집·되살리기가 지워졌고, 지운 워크로드는 회차를 "실행 중 오류: …" 로 끝냈습니다. | 엔드포인트·요청·응답·응답 코드 집합·DDL 불변. 바뀌는 것은 대상 조회 뒤 그 워크로드 차례 전에 편집·되살리기·삭제·다른 배치가 끼어든 회차(차례 전에 워크로드를 지운 즉시 실행은 500 대신 200)와 저장이 실패한 회차의 실행 후 스냅샷뿐. 단건 배치·모의 실행 무변경 |
| #151 | 2026-10-22 | 노드 · 배치 결정 · 스케줄 실행 · 통합 권고 | **실행 중 GPU 예약 유지** — 실행 중(`RUNNING`) 워크로드의 GPU 개수는 배치 10분(메트릭 지연)이 지나도 `COMPLETED`·`FAILED` 가 될 때까지 `reservedGpu` 에 남고 `availableGpu` 에서 빠집니다(3장 「노드 응답의 GPU 축」). CPU·메모리·디스크 예약은 전과 같이 빠집니다. 전에는 GPU 도 함께 빠져 쓰고 있는 카드가 비어 보였고, 다음 GPU 워크로드가 같은 카드에 배정될 수 있었습니다. | 엔드포인트·요청·응답·응답 코드 집합·DDL 불변. 값이 바뀌는 경우는 GPU 를 요구하는 실행 중 워크로드가 배치된 지 10분이 지난 노드 하나(그 노드의 `reservedGpu`·`availableGpu`, GPU 를 요구하는 배치·모의 배치·스케줄 실행·모의 실행·통합 권고의 판정). GPU 를 요구하지 않는 배치는 무변경이되, `reservedGpu` 가 `totalGpu` 보다 큰 노드는 그 배치도 받지 않고(3장 GPU 축), 임계 온도를 넘은 그 노드의 냉각 대기는 자원 부족으로 먼저 빠지는 GPU 배치 평가에서 걸리지 않아 늦게 시작될 수 있음 |
| #152 | 2026-10-22 | 노드 · 배치 결정 · 스케줄 실행 · 실행 이력 상세 · 전력 최적화 진단 · 통합 권고 | **시작 기준 자원 예약** — 실행 중(`RUNNING`) 워크로드의 CPU·메모리·디스크 예약을 배치 시각이 아니라 실행 중이 된 시각(현재 결정의 전달 기록이 `RUNNING` 이 된 시각 · 그런 기록이 없으면 배치 시각)부터 10분(메트릭 지연) 동안 남깁니다. 배치 24시간(보존 기한)은 `SCHEDULED` 에만 걸립니다(3장 「노드 응답의 GPU 축」). 전에는 배치 10분이 지나 시작한 워크로드의 CPU·메모리·디스크 예약이 시작하자마자 빠져 사용률 메트릭에 잡히기 전까지 그 노드가 실제보다 비어 보였고, 24시간 넘게 대기하다 시작한 워크로드는 시작하자마자 GPU 만 남았습니다. | 엔드포인트·요청·응답·응답 코드 집합·DDL 불변. 값이 바뀌는 경우는 실행 중 콜백을 받은 지 10분이 지나지 않았고 배치된 지는 10분이 지난 워크로드가 있는 노드(그 노드의 `reservedCpu`·`reservedMemoryMb`·`reservedDiskGb` 가 늘고 `availableCpu`·`availableMemoryMb`·`availableDiskGb` 가 줆 · 그 노드에 대한 배치·모의 배치·스케줄 실행·모의 실행·통합 권고의 판정과 점수 · MDC 전력 예산을 등록한 MDC 의 다른 노드 판정 · 전력 최적화 진단의 실효 전력·가동 판정 · 실행 상세 `clusterSnapshot` 의 전·후 칸). 예약은 전과 같거나 크고 GPU 축과 배치 대기는 무변경. 임계 온도를 넘은 그 노드의 냉각 대기는 자원·디스크 부족·사용률 초과로 먼저 빠지는 평가가 걸지 않아 늦게 시작되거나 시작되지 않을 수 있음 |
| #153 | 2026-10-23 | 배치 결정 · 배치 이력 · 스케줄 실행 · 실행 이력 상세 · 통합 권고 · 제약 조건 설명 | **GPU 를 요구하지 않는 배치의 GPU 검사 생략** — 자원 검사가 `requiredGpu = 0` 인 워크로드에는 `availableGpu` 를 보지 않습니다(3장 「노드 응답의 GPU 축」). 전에는 `reservedGpu` 가 `totalGpu` 보다 커 `availableGpu` 가 음수인 노드가 GPU 를 쓰지 않는 배치까지 `INSUFFICIENT_RESOURCE` 로 뺐고, #151 뒤로는 그 상태가 실행이 끝날 때까지 이어졌습니다. `GET /constraints` 의 C1 설명에 같은 뜻의 문장을 더했습니다. | 엔드포인트·요청·응답 형태·응답 코드 집합·DDL 불변. 노드 응답 값 불변(음수 `availableGpu` 그대로). 값이 바뀌는 경우는 `availableGpu` 가 음수인 노드에 대한 `requiredGpu = 0` 워크로드의 배치·모의 배치·스케줄 실행·모의 실행·통합 권고 판정과 점수(후보 상세 `exclusionCode` 와 이력·실행 상세 `exclusionCodes` 의 `INSUFFICIENT_RESOURCE` 가 줆 · 같은 실행의 뒤 워크로드 판정과 같은 MDC 다른 노드의 MDC 전력 예산 판정 · 실행 상세 `clusterSnapshot` 의 후 칸). GPU 를 요구하는 배치와 `reservedGpu` 가 `totalGpu` 이하인 노드는 무변경. 임계 온도를 넘은 그 노드는 GPU 를 요구하지 않는 배치 평가에서 냉각 대기가 걸림(전에는 자원 부족으로 먼저 빠져 걸리지 않음). `GET /constraints` C1 설명 문장 하나 추가(형태 불변) |
| #154 | 2026-10-23 | 배치 결정 · 배치 이력 · 스케줄 실행 · 실행 이력 상세 · 냉각 대기 · 제약 조건 설명 | **온도 상한별 냉각 대기** — 냉각 대기를 노드마다 그 대기를 건 온도 상한별로 따로 두고, 평가는 자기 상한과 같거나 더 높은 상한으로 걸린 냉각 대기에만 막힙니다(3장 주의 16). 전에는 냉각 대기가 노드에 하나뿐이라, 낮은 온도 상한(스케줄이 낮춘 상한 등)으로 걸린 냉각 대기가 상한이 더 높은 정책의 배치(기본 정책의 단건 배치 포함)까지 10분 동안 `COOLING_DOWN` 으로 막았고, 그동안에는 노드가 높은 상한까지 넘어도 그 상한의 냉각 대기가 걸리지 않았습니다. `GET /constraints` C3 설명과 냉각 대기 목록 Swagger 설명에 같은 뜻의 문장을 더했습니다. | 엔드포인트·요청·응답 형태·응답 코드 집합·DDL 불변. 판정이 바뀌는 경우는 온도 인지를 켠 평가끼리 상한이 다를 때: 낮은 상한의 냉각 대기가 살아 있는 동안 더 높은 상한으로 평가하면 그 노드가 `COOLING_DOWN` 대신 현재 온도와 나머지 제약·점수로 판정되고(후보 상세·이력·실행 상세의 제외 코드와 선택 노드가 달라질 수 있음), 그 평가에서 높은 상한도 넘으면 그 상한의 냉각 대기가 새로 걸려 낮은 상한 평가의 남은 분이 전보다 길 수 있음. 0 이하 스케줄 상한의 냉각 대기는 상한이 그 값 이하인 평가만 막음(정책 상한은 0 초과). 상한이 하나여도 만료 확인과 새 냉각 대기 기록이 겹치면 전에는 새 대기가 지워질 수 있었고 이제는 남음. 이 겹침 말고는 같은 상한끼리 무변경이고, 온도 인지를 끈 정책·통합 권고·배치 전략 비교·노드 응답도 무변경. 냉각 대기 목록은 노드마다 한 행 그대로(남은 분은 그 노드의 냉각 대기가 모두 풀릴 때까지). `GET /constraints` C3 설명 문장 하나 추가(형태 불변) |
| #155 | 2026-10-26 | 통합 권고 | **통합 권고의 냉각 대기·운영 설정 반영** — 통합 권고가 워크로드를 옮겨 보는 계산에서 받을 노드를, 기본 설정으로 새로 만든 평가기 대신 실제 배치와 같은 평가기로 판정합니다(4장 주의 5). 전에는 운영에서 바꾼 설정(유휴 전력 비율 · 열관성 계수 · 냉각 시간)과 배치가 건 냉각 대기를 보지 않아, 실제 배치라면 빠질 노드로 옮길 수 있다고 답했습니다. 계산 중 냉각 대기를 새로 걸지 않는 것은 전과 같습니다. | 엔드포인트·요청·응답 형태·응답 코드 집합·DDL·설정 키 불변. 값이 바뀌는 경우는 두 가지: 받을 노드 후보에 통합 권고 정책의 온도 상한 이상으로 걸린 냉각 대기가 살아 있을 때, 그리고 위 세 설정을 기본값과 다르게 둔 배포. 이때 `candidates[].reclaimable`·`basis`·`moves[]`·`relocationCostWatts`·`netSavingWatts`·`blockedReason`, `feasible`, 요약의 반납 수·절감량이 달라질 수 있음. 둘 다 아니면 무변경이고, 노드가 1대면 옮길 곳이 없어 무변경. 배치 결정·냉각 대기 목록·배치 전략 비교도 무변경 |
| #156 | 2026-10-26 | 배치 결정 · 배치 이력 · 실행 이력 상세 · 워크로드 | **엔진별 노드 배치 스코어의 뜻** — 배치 엔진이 후보 노드마다 매기는 다섯 항목별 스코어와 총 스코어가 엔진마다 담는 값을 3장에 표로 적고, 이력을 모을 때 엔진별로 나누는 규칙을 더했습니다. 전에는 필드 이름만 있어, temperature 엔진의 `networkScore`(열적 균형)를 다른 엔진의 트래픽 스코어와 함께 평균하는 식으로 뜻이 다른 값을 섞어 집계할 수 있었습니다(3장 주의 4 정정). 값·형태·운영 코드 변경 없음 | 변경 없음. 엔진을 섞어 스코어를 모으던 화면은 `engineName` 으로 나눠야 함 |
| #158 | 2026-10-27 | 벤치마크 | **판정 대상 지정과 기준선 재측정** — 공석이던 다음 판정 주어를 노드 배치 스코어 PEI 항의 코어 수 무시로 지정하고(8장 「판정 주어」), 규칙 축이 돌아 판정 기준선을 다시 떴습니다(8장 「판정 기준선과 보고 절차」). 엔진·판·설정 코드는 그대로입니다. | 엔드포인트·경로·스키마·응답·DDL 불변 · 엔진·판·설정 무접촉 · 운영 코드는 규칙 문장(`JUDGMENT_TARGET`)과 그 주석만 바뀜 — 화면 영향 없음 |
| #160 | 2026-10-27 | 배치 결정 · 배치 이력 · 스케줄 실행 · 실행 이력 상세 · 통합 권고 · 배치 전략 비교 | **노드 배치 스코어 전력 효율 항의 코어 수 반영** — power 엔진 후보 상세 `PEI` 를 실효 사용률 × (그 노드 코어 수 ÷ 그 배치 호출 후보 중 코어 수가 0 보다 큰 노드의 평균 코어 수) ÷ 실효 전력으로 냅니다(8장 「판정 주어」). 평균은 제외 판정을 받은 노드와 전력을 계측하지 못한 노드를 포함한 후보 전체로 냅니다. 코어 수가 모두 같거나 후보가 한 대면 값이 비트 단위로 그대로입니다. 진단 API(전력 효율·최적화)의 PEI 는 그대로라, 같은 노드의 두 PEI 는 같은 이름에 다른 정의입니다 — 같은 값으로 나란히 비교해 보이지 마십시오. 비교 판 판정 자격 2판 `power` 행 판정 셀 11.8396 → **9.7150** · 방향 셀 16.6866 → **14.2874**(판정 결말 `PASS`, 8장 판정 이력). 다음 판정의 출발선은 다음 회차 앞 재측정 PR 이 기준선 파일로 확정합니다. 판·워크로드 다이제스트·규칙 축 8값·`configDigest 1487c10f4e97` 불변, 증거 묶음 `entriesDigest 0cafa69fbcc9` → `6d9356bed3f6` | 엔드포인트·경로·요청·응답 형태·응답 코드·DDL·설정 키·기본값 불변. 값은 전력·정격이 계측된 후보가 있고 코어 수가 섞일 때만 바뀜(후보 상세 `PEI`·`전력점수` · `powerScore`·`totalScore` · 정렬·선택·`finalScore`·`reason`·`통합여유` · 배치 이력의 선택 노드·스코어 · 통합 권고 `moves`·이동 비용·순절감과 `NET_SAVING` 판정 · 비교 API power 5열 · 전 열의 배치 건수가 같은 지점이 바뀐 셀에서는 비전력 열의 총량·온도 분포 표본도 바뀌고, 셀마다 전 열에 같은 값으로 싣는 미계측 격차도 바뀔 수 있음). **배포 서버는 전력 계측이 없어 응답이 바이트 단위로 불변**이고 판 파일 기반 비교 응답만 다음 배포 뒤 움직입니다 |
| #161 | 2026-10-27 | 벤치마크 | **판정 대상 비움과 기준선 재측정** — 회차 4 지목(노드 배치 스코어 PEI 항의 코어 수 무시, `PASS`)을 소비 목록에 내리고 다음 판정 주어를 공석으로 두었으며(8장 「판정 주어」), 규칙 축이 돌아 판정 기준선을 다시 측정했습니다(8장 「판정 기준선과 보고 절차」). 엔진·판·설정 코드는 그대로입니다. 공석인 동안에는 증거 묶음 `entriesDigest` 를 바꾸는 변경을 미룰 필요가 없습니다. | 엔드포인트·경로·스키마·응답·DDL 불변 · 엔진·판·설정 무접촉 · 운영 코드는 규칙 문장(`JUDGMENT_TARGET`·`GAP_JUDGMENT_RULE`)과 전력 엔진 주석 한 줄만 바뀜 — 화면 영향 없음 |
| #162 | 2026-10-28 | 스케줄 · 냉각 대기 | **저장 전 모의 실행의 온도 상한** — 저장 전 모의 실행 `POST /schedules/dry-run` 이 스케줄 온도 상한(`maxTemperatureCelsius`)을 지정한 요청도 받아 그 값으로 평가합니다(2장 주의 15 · 3장 주의 16). 온도 상한을 지정하면 냉각 대기를 새로 걸지 않고, 이미 걸린 냉각 대기는 상한별로 봅니다. 전에는 온도 상한을 지정하면 400 이라, 온도 상한이 있는 스케줄은 저장한 뒤에야 배치 결과를 볼 수 있었습니다. | 엔드포인트·요청·응답 형태·DDL·설정 키 불변 · 온도 상한을 지정해 400 이던 요청이 다른 요청과 같이 평가됨(대개 200 · 지정한 정책 id 가 없으면 404) · 온도 상한을 지정하지 않은 요청과 저장된 스케줄의 모의 실행은 그대로 · 배치 전략 비교 무변경 — 400 메시지로 분기하던 화면은 그 분기를 지워야 함 |
| #163 | 2026-10-28 | 스케줄 실행 이력 | **스케줄 실행 입력 저장** — 스케줄 실행(자동·즉시)이 배치 루프 직전의 입력(노드 상태·정책 사본·링크·MDC 예산·실행 전력 상한·냉각 대기·대상 워크로드·엔진 설정)을 새 표 `schedule_run_inputs` 에 실행 1회당 한 행으로 남깁니다(2장 「실행 입력 저장」). 실행 이력과 함께 지워지고, 그 회차를 다시 돌려 볼 수 있습니다. | 엔드포인트·요청·응답·Swagger 불변 · DDL 새 표 1개(`deploy/sql/2026-10-28_schedule_run_inputs.sql` — 기본 프로파일 validate 인 DB 는 배포 전에 적용, 배포 VM 은 update 라 자동 생성) · 기존 표 무변경 · 배치 전략 비교 무변경 · 화면 영향 없음 |
| #164 | 2026-10-29 | 스케줄 실행 이력 | **실행 재생 `GET /schedules/{id}/runs/{runId}/replay`** 신설(2장 「실행 재생」) — 저장한 실행 입력으로 그 실행의 배치를 지금 코드로 다시 계산해 워크로드별로 배치 이력의 선택 노드·노드 배치 스코어·성공 여부와 대조하고 `MATCH`·`MISMATCH`·`INCOMPLETE` 로 판정합니다. 화면이 부르지 않는 검증용이며 DB 에 아무것도 저장하지 않습니다. | 엔드포인트 +1(75 → 76) · 스키마 +3(응답 · 단계 · 공용 래퍼) · 기존 경로·응답·DDL 불변 · 배치 결정·배치 전략 비교 무변경 · 화면 영향 없음 · 옛 서버는 이 경로가 없어 403 |
| #165 | 2026-10-29 | 스케줄 실행 이력 | **엔진별 재계산 `GET /schedules/{id}/runs/{runId}/replay/engines`** 신설(2장 「엔진별 재계산」) — 저장한 실행 입력 하나로 운영 엔진 세 개를 각각 다시 계산해 워크로드별 선택 노드·성공 여부를 배치 이력과 나란히 돌려줍니다. 노드 배치 스코어·평가 지표·순위는 싣지 않으며 엔진 우열을 말하는 응답이 아닙니다. 화면이 부르지 않는 검증·연구용이며 DB 에 아무것도 저장하지 않습니다. | 엔드포인트 +1(76 → 77) · 스키마 +5(응답 · 엔진 · 단계 · 선택 · 공용 래퍼) · 기존 경로·응답(실행 재생 포함)·DDL 불변 · 배치 결정·배치 전략 비교 무변경 · 화면 영향 없음 · 옛 서버는 이 경로가 없어 403 |
| (이번) | 2026-10-29 | 단건 배치 | **단건 배치 입력 저장** — 단건 배치(`dryRun=true` 가 아닐 때)가 배치 직전 입력과 엔진 결정(실제로 쓴 엔진 · 성공 여부 · 선택 노드)을 새 표 `placement_inputs` 에 1회당 한 행으로 남깁니다(2장 「실행 입력 저장」). 실패한 결정도 남기며, 실행 이력과 같은 보존 기간이 지나면 지웁니다. | 엔드포인트·요청·응답·Swagger 불변 · DDL 새 표 1개(`deploy/sql/2026-10-29_placement_inputs.sql` — 기본 프로파일 validate 인 DB 는 배포 전에 적용, 배포 VM 은 update 라 자동 생성) · 기존 표 무변경 · 배치 이력 의미 불변(단건 배치의 실패 결정은 여전히 이력에 없음) · 배치 결정·배치 전략 비교 무변경 · 화면 영향 없음 |

예정 항목은 착수 시점에 날짜를 채우고, PR 번호는 병합된 뒤 채웁니다. `(이번)` 은 작성 당시 PR 번호가 없던 변경이며, 병합된 뒤 다음 계약 변경 때 번호를 채웁니다.
