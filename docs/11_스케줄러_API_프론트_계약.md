# 11. 스케줄러 API 프론트 계약 — 지능형 예측 진단 (스케줄러 · 전력 최적화 진단)

- 작성일: 2026-09-10
- 대상 화면: 지능형 예측 진단 > 스케줄러, 지능형 예측 진단 > 전력 최적화 진단
- 기준 코드: `com.innogrid.aims.scheduler` (develop `e12d665` + 문구 정정 PR), 엔드포인트 59개
- 관련 문서: docs/10 알람 API 프론트 계약(같은 형식), docs/06 자원 탐색기 트리 설계(역할 스코프)

**필드 하나하나의 진실은 Swagger(`/swagger-ui/index.html`, `/v3/api-docs`)입니다.** 이 문서는 필드를 전수로 옮기지 않습니다. 대신 화면을 붙일 때 Swagger 만으로는 알기 어려운 것 — 경로 묶음, 공통 규칙, 값이 비는 조건, 오류가 나는 조건, 그리고 앞으로 바뀔 예정 — 을 적습니다. 필드 목록이 필요하면 Swagger 를 보고, 이 문서와 다르면 코드가 맞습니다.

---

## 1. 공통

| 항목 | 규칙 |
|---|---|
| 기본 경로 | `/api/v1/scheduler` |
| 인증 | `Authorization: Bearer <JWT>`. 전 경로 인증 필요 |
| 응답 래퍼 | `{ success, data, message }`. 실패면 `success=false` 이고 `message` 에 한국어 사유 |
| 목록 | 페이징은 `data` 가 `{ items, page, size, totalElements, totalPages }`. **`page` 는 1부터** |
| 배열 응답 | 페이징이 아닌 목록은 `data` 가 배열(노드·엔진·제약·이력 등) |
| 정렬 | `sort={필드},{asc|desc}`. 허용 밖이면 400 이고 메시지에 가능한 값을 나열 |
| 열거형 | 전부 대문자 문자열. 요청 시 소문자를 받아 주는 자리가 일부 있으나 응답은 항상 대문자 |
| 시각 | ISO-8601 offset 문자열(`2026-09-10T09:00:00+09:00`) |
| 오류 | 400 검증 실패(사유 한국어), 404 대상 없음, 409 상태 충돌, 429 동시 실행 제한(배치 전략 비교 한 곳만 — 8장). **스케줄러 경로는 필수 파라미터가 빠져도 400 과 사유를 줍니다** |

### 1.1 정렬 허용 필드

| 대상 | 기본값 | 허용 필드 |
|---|---|---|
| 스케줄 목록 | `createdAt,desc` | scheduleId, name, resourceType, cronExpression, description, autoRun, enabled, lastRunAt, lastRunFinishedAt, lastRunStatus, nextRunAt, createdAt, updatedAt |
| 실행 이력 | `startedAt,desc` | startedAt, finishedAt, status, triggerType, targetCount, successCount, failedCount |
| 배치 이력 | `createdAt,desc` | createdAt, finalScore, workloadName, success |

### 1.2 상태 값

| 대상 | 값 |
|---|---|
| 워크로드 | `PENDING` `SCHEDULED` `RUNNING` `COMPLETED` `FAILED` |
| 실행 | `RUNNING` `COMPLETED` `FAILED` · 실행 주체 `AUTO` `MANUAL` |
| 배치 결정 전달 | `REQUESTED` `ACCEPTED` `RUNNING` `COMPLETED` `FAILED` `REJECTED` `UNAVAILABLE` `TIMED_OUT`(원격 모드 전용 — 지금의 record 모드에서는 나오지 않음) |
| 리소스 종류 | `VM` `GPU` `NPU` `CONTAINER` |
| 배치 순서 | `PRIORITY_THEN_AGE` `PRIORITY_THEN_SIZE` |
| 엔진 | `weighted` `power` `temperature` (소문자 — 엔진 식별자라 예외) |

---

## 2. 스케줄 — `/api/v1/scheduler/schedules` (9)

배치 스케줄 화면입니다.

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET | `/` | 목록. `keyword`, `resourceType`, `enabled`, `page`, `size`, `sort` |
| GET | `/{id}` | 상세 |
| POST | `/` | 생성 |
| PUT | `/{id}` | 편집(부분 갱신) |
| DELETE | `/?ids=1,2,3` | 삭제 |
| POST | `/{id}/run` | 즉시 실행 |
| GET | `/{id}/runs` | 실행 이력(페이징) |
| GET | `/{id}/runs/{runId}` | 실행 상세 — 그 실행의 배치 결정 목록 포함 |
| POST | `/cron/validate` | 실행 주기 검증 + 다음 실행 5건 미리보기 |

**화면을 붙일 때 주의할 것**

1. **삭제는 요청 본문이 아니라 쿼리 파라미터**입니다. `DELETE /schedules?ids=1,2,3`.
2. **생성 필수는 셋뿐**입니다 — 스케줄 ID, 스케줄명, 실행 주기. 나머지는 모두 선택이며 리소스 종류를 비우면 실행 때 VM 으로 해석합니다.
3. **스케줄 ID 는 편집에서 바꿀 수 없습니다.** 보내려면 기존 값과 같아야 하고 다르면 400 입니다.
4. **다음 실행 시각은 자동 실행과 사용 여부가 둘 다 켜져 있을 때만** 값이 있습니다. 하나라도 꺼져 있으면 `null` 입니다.
5. **실행 주기는 여섯 자리**(초 분 시 일 월 요일)이고 **요일 숫자가 Quartz 와 한 칸 다릅니다.** 화면에서 만든 값은 `cron/validate` 로 다음 실행 시각을 먼저 보여 주는 편이 안전합니다.
6. 시간대는 서버 설정값이며 기본은 `Asia/Seoul` 입니다. 응답의 `timezone` 을 그대로 쓰십시오.

---

## 3. 워크로드 · 노드 · 이력 — `/api/v1/scheduler` (20)

진단 화면과 스케줄 화면이 함께 씁니다.

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET / POST | `/workloads` | 목록(`status` 필터) / 생성 |
| GET / DELETE | `/workloads/{id}` | 상세 / 삭제 |
| GET | `/workloads/resource-types` | 리소스 종류 목록 |
| PATCH | `/workloads/{id}/status` | 상태 변경 |
| POST | `/workloads/resume-exhausted` | 재시도 소진 워크로드 일괄 재개. `resourceType`, `from`, `to` |
| POST | `/workloads/{id}/schedule` | 단건 배치. `engine`, `dryRun` |
| GET | `/nodes`, `/nodes/{nodeId}` | 노드 목록·상세 |
| GET | `/nodes/excluded` | 수집 실패로 후보에서 빠진 노드와 사유 |
| GET | `/nodes/power-efficiency` | 노드별 전력 효율 지표 |
| GET | `/nodes/{nodeId}/power-efficiency/trend` | 효율 추세. `from`, `to`, `step` |
| GET | `/constraints` | 제약 조건 설명(C0~C6) |
| GET | `/thermal/cooldowns` | 냉각 대기 중인 노드 |
| GET | `/engines` | 선택 가능한 엔진 `[{id, name}]` |
| GET | `/history` | 배치 이력. 최신순 배열, **최근 1000건까지** |
| GET | `/history/page` | 배치 이력 페이징. 상한 없음 |
| GET | `/workloads/{id}/history` | 워크로드별 배치 이력 |
| GET | `/metrics-source` | 지금 보고 있는 메트릭 출처 |

**화면을 붙일 때 주의할 것**

1. **상태 변경 API 로 배치 상태(SCHEDULED)로 바꿀 수 없습니다.** 배치는 엔진이 정합니다. 실패한 워크로드는 대기로만 되살릴 수 있고, 되살리면 재시도 횟수가 0 으로 지워집니다.
2. **전력·온도를 수집하지 못한 노드는 값이 0 으로 옵니다.** `powerSource` 가 `NONE` 이면 센서가 없는 노드이니 화면에서 "-" 로 처리하십시오. 현재 배포 서버가 이 상태입니다.
3. **단건 배치에 `dryRun=true`** 를 주면 저장 없이 결과만 돌려줍니다. 모의 배치 화면에 그대로 쓸 수 있습니다.
4. 배치 이력에는 최종 점수와 항목별 점수(CPU·메모리·전력·온도·네트워크), 선택 당시 노드 상태, 후보 수, 사유, 엔진명, 정책명이 함께 있습니다.
5. `/history` 는 배열이고 상한이 있습니다. 전체가 필요하면 `/history/page` 를 쓰십시오.
6. **재시도에 15분 간격이 있습니다.** 배치에 실패한 워크로드는 곧바로 다시 시도하지 않고 15분 뒤부터 대상이 됩니다. 실행 결과가 "대상 없음 (재시도 대기 N건 — 15분 뒤 다시 시도합니다)" 이면 정상이며 기다리면 됩니다.
7. **"대상 없음 (재시도 소진 N건)" 은 실행이 실패로 끝납니다.** 사람이 되살리기 전에는 그 스케줄이 아무 일도 못 하기 때문입니다. 일괄 재개 API 를 안내하십시오.
8. **일괄 재개는 리소스 종류나 기간 중 하나가 필수**이고 한 번에 500건까지입니다. 조건 없이 부르면 400 입니다. 응답은 `resumedCount` 와 되살린 워크로드 목록입니다.
9. **엔진 목록의 `name` 은 자바 클래스명**(`PowerAwareScheduler` 등)입니다. 화면에 그대로 노출하기 어려우니 `id`(`weighted`·`power`·`temperature`)로 판단하고 표시 문구는 프론트에서 정하십시오. 표시명 추가는 후속 과제로 두었습니다.

---

## 4. 전력 최적화 진단 — `/api/v1/scheduler/optimization` (3)

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET | `/power` | 현재 진단 — 클러스터·MDC 별·개선 항목 |
| GET | `/power/trend` | 추세. `from`, `to`, `step` |
| GET | `/consolidation` | 통합 배치 권고 — 반납 가능 노드 후보 |

**화면을 붙일 때 주의할 것**

1. **계측하지 못한 노드는 계산에서 빠집니다.** 응답의 `measured`, `unmeasuredReason`, `excludedNodes` 가 그 사실을 함께 내려주므로 화면에 근거로 표시하십시오.
2. **통합 권고는 권고까지입니다.** 실제 이전은 하지 않습니다. 후보마다 옮길 워크로드 목록, 유휴 전력, 이전 비용, 순 절감, 불가 사유가 있습니다.
3. 추세는 조회 시점에 계산합니다. 저장된 시계열이 아니므로 구간이 길면 응답이 느립니다.

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
- 필드는 가중치 다섯(CPU·메모리·전력·온도·네트워크), 임계 넷(CPU·메모리·온도·전력 사용률), 인지 스위치 셋(전력·온도·친화도), 경고 온도, 기본 엔진입니다.

---

## 6. 운영자 입력 원장 (16)

| 대상 | 경로 | 메서드 |
|---|---|---|
| 노드 정격 전력 | `/node-power-specs` | GET `/` · GET `/{specId}` · POST `/` · PUT `/{specId}` · DELETE `/{specId}` |
| MDC 전력 예산 | `/mdc-power-budgets` | GET `/` · GET `/{budgetId}` · POST `/` · PUT `/{budgetId}` · DELETE `/{budgetId}` |
| MDC 링크 | `/mdc-links` | GET `/`(`mdcId`·`status` 필터) · GET `/matrix` · GET `/{linkId}` · POST `/` · PUT `/{linkId}` · DELETE `/{linkId}` |

센서로 알 수 없는 값(노드 정격 W, MDC 총량 W, MDC 간 지연·대역폭)을 운영자가 입력하는 화면입니다.

- **정격이 등록되지 않은 노드는 기본값 500W 로 계산합니다.** 원장이 비어 있으면 전 노드가 같은 값을 쓰므로, 화면에 "미등록" 을 구분해 보여 주는 편이 좋습니다.
- MDC 링크의 상태가 `DOWN` 이면 그 MDC 로는 배치하지 않습니다. `UP`·`DEGRADED` 의 지연과 손실은 제약이 아니라 배치 점수에만 반영됩니다.
- 정책의 `affinityEnabled` 가 켜져 있고 워크로드에 `dataMdcId` 가 있을 때만 링크가 배치에 반영됩니다. 이때 후보 노드 상세(`details`)에 `링크품질`(같은 MDC 1.0 · 등록 링크는 지연·손실 기반 · **원장에 없는 원격 MDC 는 기본 0.5**)과 `링크등록`(1.0 등록됨 또는 같은 MDC, 0.0 원장에 없음)이 붙습니다. 원장에 없는 원격 MDC 는 배치에서 빠지지 않고 점수만 낮습니다 — 링크를 등록하면 측정값으로 바뀝니다. `링크등록` 은 **원장에 행이 있는지**만 뜻하므로 `DOWN` 링크도 `1.0` 입니다. 배치 가능 여부는 `eligible` 과 제외 사유로 판단하십시오. `dataMdcId` 는 앞뒤 공백을 지운 뒤 노드의 MDC 라벨과 **정확히 일치**해야 같은 MDC 로 봅니다(대소문자 차이는 미등록 원격으로 처리, 공백만이면 지정 안 함).

---

## 7. 배치 결정 전달 기록 — `/api/v1/scheduler` (4)

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET | `/dispatches` | 목록. `status`, `workloadId`, `scheduleRunId`, 페이징 |
| GET | `/dispatches/{dispatchId}` | 상세 |
| POST | `/dispatches/{dispatchId}/result` | 실행 결과 콜백 — **실행 계층이 호출**합니다 |
| GET | `/workloads/{workloadId}/dispatches` | 워크로드별 전달 이력 |

지금은 배치 결정을 기록하는 데까지 동작합니다. 실제 생성·이동은 실행 계층 API 가 준비되면 붙습니다.

---

## 8. 배치 전략 비교 — `/api/v1/scheduler/benchmark/placement` (1)

우리 엔진과 대조군(선착순 두 변종 · 엔진 전력식 공유 ΔP 단독 · 자원 최밀집 · 단순 규칙 · 무작위), 그리고 **엔진 설정 변종 7열**(자원전용 2 · 링크 게이트 on 1 · 한계 전력 눈금 3 · 유휴 역산 차단 1 — 열마다 `strategies[].engineConfigOverride` 가 바뀐 노브를 밝히고 그 값은 설정 다이제스트에 들어갑니다)을 같은 입력으로 비교한 결과입니다. 셀당 열은 16 입니다. **연구 검증·성능 보고용이며 화면 연동 대상이 아닙니다.**

**판 10장.** main · adversarial · sparse · convex · misrated · convex-misrated(판정판) 여섯 장에 더해 `link`(주 결과판 노드 + `links:` 절 · 데이터 MDC 순환 배정 — 링크 점수를 섞는 엔진은 weighted 하나뿐이라 값이 갈리는 열은 `weighted`/`weighted-affinity` 쌍이며 링크는 판 파일이 원장 노릇을 하고 DB 원장은 여전히 0건), `main-independent` · `convex-misrated-independent`(짝 판과 노드 동일, `phaseMode: independent` · `seeds` 3 → `boards[]` 가 seed 별 3장, 서로 독립 복제라 Board 사이의 시각 지점을 합쳐 세지 않습니다), `concave`(주 결과판과 생성식 지수 0.625 만 다른 판). **판정·방향 판정 자격은 `convex-misrated` · `main` 두 판의 `power` 행에만 있고 그 밖의 판·열·지표는 전부 자격이 없습니다**(폐쇄형 — 판·열이 늘어도 이 문장은 바뀌지 않습니다).

응답의 `board.digest` 는 판 파일 내용(개행을 LF 로 정규화)의 SHA-256 앞 12자, `engineConfig.digest` 는 엔진 설정 정규 문자열의 해시입니다. 두 값이 같으면 판 파일과 엔진 설정이 같다는 뜻입니다 — 워크로드 분포 파일과 판정 규칙은 응답 다이제스트에 들어가지 않으니(분포는 이름으로만 지목), 두 표를 견줄 때는 같은 소스 리비전에서 낸 것인지를 함께 확인합니다. 설정 다이제스트에는 네트워크 노브(`engineConfig.network`)와 열별 override(`overrides=` 세그먼트)가 들어가며, 그 성분은 전부 응답에 노출됩니다. 판 다이제스트는 빌드 OS 와 무관하며 `git show HEAD:파일 | sha256sum` 으로 재계산할 수 있습니다. 2026-09-11 이전 Windows 빌드가 낸 표는 10장 변경 이력의 옛→새 대응표로 대조합니다. 재현 대조 시에는 `evaluatedAt`(응답 생성 시각)만 제외하고 본문을 비교합니다 — 그 외 비결정 필드는 없습니다(난수 시드 고정, 맵 필드는 키 정렬로 직렬화).

**캐시와 동시 실행 제한.** 응답은 `evaluatedAt` 을 빼면 입력(판 · 분포 · 판 파일 다이제스트 · 워크로드 파일 다이제스트 · 설정 다이제스트 · 판정 규칙 다이제스트)의 순수 함수라, 서버가 같은 입력의 본문을 메모리에 보관하고 두 번째 호출부터는 `evaluatedAt` 만 새로 찍어 돌려줍니다 — 저장소(DB)에는 여전히 아무것도 쓰지 않으며 재시작하면 비워집니다(운영 중 비우는 수단은 없습니다. 키 조합이 판 10 × 분포 2 = 20 으로 고정이라 — seed 는 판 파일 값이고 요청 축이 아닙니다 — 내부 상한 25 에는 닿지 않고, 판·설정이 모두 jar 안이라 변경은 곧 재배포입니다). 계산은 한 번에 한 건만 돌고, 계산 중 들어온 호출 중 **캐시에 없는 입력**만 기다리지 않고 **429** 와 한국어 사유를 받습니다 — 캐시에 있는 입력은 계산 중에도 200 이고, 모르는 판·분포는 계산 중에도 400 입니다(입력 검증이 캐시·동시 제한보다 앞섭니다). 재호출 간격은 수 초~수십 초(배포 서버 2 vCPU 실측: 첫 호출 main 7.7초, 다른 판 4~5초 — 위상 독립 판은 Board 3장이라 약 3배)이며 `Retry-After` 헤더는 주지 않습니다(공통 오류 래퍼를 건드리지 않기 위해 — 간격은 `message` 안내를 고정값으로). 429 는 서버 로그에 WARN 으로 남습니다. 배포 서버는 단일 인스턴스라 이 제한은 프로세스 단위입니다.

---

#### 판정 규칙 축 다이제스트(연구 재현용 · 화면 영향 없음)

판정 규칙(지표 키·방향·역할, 부하율·지점 수·시드, 판 상수, 판정 문턱, 금지 조각, 절차 문장)은 축별 정규 문자열의 SHA-256 앞 12자로 봉인되어 계약 시험의 이력 표와 증거 묶음 매니페스트(`ruleAxisDigests`)에 실립니다. 응답에는 싣지 않습니다. 축이 바뀌는 커밋은 제목을 `rules:` 로 시작하고 결과 코드 변경을 같은 커밋에 담지 않으며, 아래 표와 시험의 이력에 행을 더합니다. 증거 묶음의 생성 방법은 매니페스트의 `regeneratedBy` 에 있습니다.

| 날짜 | 축 | 옛 → 새 | 사유 |
|---|---|---|---|
| 2026-09-14 | metrics · sweep · boards · measured · judgment · banned · prose | (없음) → 시험 이력 표의 현재 값 | 축 봉인 도입 |
| 2026-09-14 | boards | `2a0f6a76ba2f` → `9ab95b89dc3e` | 방법론 변경 사전 등록 — 오목 지수 0.625 · 오목/링크 판 id · 위상 독립 판 2장 · seed 수 3 · SplitMix64 혼합 상수 |
| 2026-09-14 | sensitivity | (없음) → `3741559c00dd` | 축 신설 — 한계 전력 눈금 3값(250·1000·0) · 역산 차단 열 · 링크 게이트 열 |
| 2026-09-14 | prose | `ae0fbae7a919` → `b37d0ec2006e` | 폐쇄형 판정 자격 문장 · 민감도 검정 · 위상 구성 · 오목 판 결론 형식 |
| 2026-09-14 | measured | `0189eec53480` (불변) | 판정 시점 고정값(316) 으로 의미 확정 — 현 실측(오목 판 345.5W)은 판 데이터라 축 밖 |

---

## 9. 지금 배포 서버에서 값이 비는 것

화면 개발 중 데이터가 없다고 의심되면 아래를 먼저 확인하십시오. 코드 문제가 아닙니다.

| 항목 | 현재 | 이유 |
|---|---|---|
| 노드 목록 | 1건 | 모니터링 대상 노드가 백엔드가 도는 서버 하나뿐 |
| 전력·온도 | 0 | IPMI 수집 대상 미등록 |
| GPU | 0 | DCGM 수집 대상 미등록 |
| 원장 3종 | 0건 | 운영자 입력 전 |
| 링크 원장 | 0건 | 운영자 입력 전. 그래서 배치 후보 상세에 `링크품질`·`링크등록` 키가 나오지 않는다(정책 `affinityEnabled` 도 꺼져 있음) |
| 통합 권고 | 후보가 적음 | 노드가 1대라 옮길 곳이 없음 |

---

## 10. 변경 이력

응답에 필드가 늘어나는 변경은 기존 계약을 깨지 않지만, 프론트가 키 집합을 고정해 두었다면 영향을 받습니다. 변경이 생기면 이 표에 먼저 적고 공유합니다.

| PR | 날짜 | 대상 | 변경 | 호환성 |
|---|---|---|---|---|
| #49 | 2026-09-10 | 배치 전략 비교 | `engineConfig.power.marginalScaleWatts` 추가, 설정 다이제스트 변경 | 필드 추가. 화면 영향 없음 |
| #51 | 2026-09-10 | 워크로드 | `lastFailedAt` 추가(21 → 22필드), 일괄 재개 엔드포인트 신설(58 → 59) | 필드 추가. 키 집합을 고정한 화면은 한 번 조정 필요 |
| #53 | 2026-09-11 | 배치 결과 후보 상세 | `affinityEnabled` 정책 + `dataMdcId` 워크로드 배치에서 `details.링크등록` 추가 · 원장에 없는 원격 MDC 의 `링크품질` 1.0 → 0.5(설정 `aims.scheduler.network.unknown-link-score`) | 필드 추가. 게이트를 켜지 않은 배치는 응답·점수 불변. 켠 배치는 미등록 원격 후보 총점 −0.0375(기본 가중치·NORMAL 우선순위·선호 보너스 없음 기준. CRITICAL 은 ×1.2, 정책 가중치·설정값에 비례) |
| (이번) | 2026-09-11 | 배치 전략 비교 | `board.digest` 를 개행 정규화한 내용의 해시로. 판 yml·판정 규칙·계산은 무변경. Windows 빌드 jar 의 여섯 판 값이 저장소(LF) 기준값으로 바뀜 — main `add880e1f395`→`fb011848258b` · adversarial `4ce3ca80c771`→`b89c82edff1c` · sparse `cecd68498459`→`f2109dcf47e1` · convex `84f26146719d`→`5e71beea2592` · misrated `a1d780187595`→`e935750a1637` · convex-misrated `bdbbfd0f5b06`→`d199d46fa3b0`. LF 체크아웃 빌드는 불변 | 필드·형태 변경 없음. 값만 변경. 화면 연동 대상 아님 |
| (이번) | 2026-09-14 | 배치 전략 비교 | 대조군 라벨 값 변경 — `pabfd` `전력 인지 최적적합(문헌 기준선)` → `전력 인지 최적적합(엔진 전력식 공유 · ΔP 단독 · 깨우기항 없음)`, `coolest-first` `최저 온도 우선(문헌 기준선 · 재평가 없음)` → `최저 온도 우선(단순 규칙 · 재평가 없음)`. Swagger 설명 문구 정정. 전략 id·지표 키·다이제스트 불변 | 필드·형태 변경 없음. `strategies[].label` 값만 변경 — 라벨을 키로 쓰는 화면은 조정 |
| (이번) | 2026-09-14 | 배치 전략 비교 | 같은 입력의 두 번째 호출은 메모리 캐시에서(`evaluatedAt` 만 갱신), 계산 중 동시 호출(캐시 미적중)은 **429**(Swagger 응답 스키마에 200/400/429 선언). 맵 필드(`capacityByNode`·`probeExclusionReasons`) 키 정렬 직렬화. 판정 규칙 축 봉인(8장 소절 — 화면 영향 없음). 판 파일 `links:` 절 파서 추가(등록된 판은 절이 없어 다이제스트·값 불변). 판 등록부 레코드화 · 금지 문구 소스 검사 · 증거 묶음 시험 | 형태 변경 없음. 새 오류 코드 429 한 건 — 화면 연동 대상 아님. 맵 키 순서만 정렬로 고정(값 불변) |
| (이번) | 2026-09-14 | 배치 전략 비교 | 엔진 설정 정규 문자열에 `network=`(링크 점수 노브 4)·`overrides=`(열별 노브) 세그먼트 편입 — `engineConfig.configDigest` **`79315785b762` → `1487c10f4e97`**. 판 파일·판정 규칙·기존 11열의 값은 불변 | 다이제스트 값 변경 — 이전 표와 견줄 때 이 대응표로 대조. `engineConfig.network` 필드 추가 |
| (이번) | 2026-09-14 | 배치 전략 비교 | 판 4장 추가(`link` · `main-independent` · `convex-misrated-independent` · `concave`, 6 → 10) · 엔진 설정 변종 5열 추가(`weighted-affinity` · `power-s250` · `power-s1000` · `power-s0` · `power-idle-fixed`, 셀당 11 → 16열) · `strategies[].engineConfigOverride` · `boards[].phaseMode`·`phaseSeed` · `assumptions.workloadDataMdcAssignment` 추가 | 필드 추가 + 열 수 변경. 열 수를 고정한 화면은 조정(화면 연동 대상 아님) |
| (이번) | 2026-09-14 | 배치 전략 비교 | 위상 독립 판은 `boards[]` 가 seed 별 3장(기존 판은 길이 1 그대로). 캐시 상한 16 → 25, 429 재호출 안내 "수 초~수십 초" | 기존 판 호출 불변. 새 판만 `boards[]` 길이 3 |
| — | — | (예정) 실행 이력 | 실행 전후 클러스터 지표 추가 | 필드 추가 |
| — | — | (예정) 배치 이력 | 제외 사유 코드 추가 | 필드 추가 |
| — | — | (예정) 노드 | GPU 온도·전력 추가 | 필드 추가 |

예정 항목은 착수 시점에 날짜와 PR 번호를 채웁니다.
