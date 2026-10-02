# AIMS 노드 로그 수집 에이전트(Grafana Alloy) 설치 안내 — 세부1 물리 노드용

| 항목 | 내용 |
|------|------|
| 대상 독자 | 세부1 노드 설치 담당자 |
| 작성 | 이노그리드(세부2), 2026-10-02 |
| 전제 | 노드에 Docker(또는 Docker Compose v2)가 있고, 노드에서 AIMS 수집 서버·백엔드로 나가는 통신이 열려 있다 |
| 걸리는 시간 | 노드당 약 5분 |

## 1. 무엇을 설치하는가

AIMS 포털의 관측 > 자원 탐색기 > 노드 > 로그 탭이 이 노드의 로그를 보여 주려면, 노드에서 로그 파일을 읽어
AIMS 수집 서버의 Loki 로 보내는 에이전트가 하나 돌아야 합니다. node_exporter 가 메트릭을 내보내는 것과 같은 자리이며,
Grafana Alloy 컨테이너 1개입니다.

- 기본으로 읽는 파일: `/var/log/syslog`(또는 `messages`), `/var/log/auth.log`(또는 `secure`), `/var/log/kern.log`
- 그 외 파일은 AIMS 포털에서 운영자가 등록하면, 에이전트가 30초마다 AIMS 백엔드에서 목록을 가져가 자동으로 읽기 시작합니다.
  **AIMS 쪽에서 이 노드에 접속하는 일은 없습니다.** 에이전트가 밖으로 나가는 방향(노드 → AIMS)만 있고, 토큰이나 비밀번호도 없습니다.

```
[노드]  /var/log/*, 포털에서 등록한 파일 ──읽기──▶ Alloy ──push──▶ [AIMS 수집 서버] Loki :3100
                                                 ▲
                                                 └──30초마다 목록 조회(?instance=NODE_INSTANCE)──▶ [AIMS 백엔드] :8080 /api/v1/agent/targets
```

## 2. AIMS 쪽에서 미리 전달받을 값 (노드마다 다름)

| 값 | 설명 |
|----|------|
| `NODE_INSTANCE` | 이 노드의 node_exporter 를 Prometheus 가 긁는 주소(`host:port`). AIMS 자산관리에 등록된 값과 **글자까지 같아야** 합니다. 메트릭과 로그를 한 노드로 묶는 키이면서, 백엔드가 "어느 노드의 목록을 줄지" 판단하는 식별자입니다 |
| `MDC_ID` | 이 노드가 속한 마이크로 데이터센터 id |
| `LOKI_PUSH_URL` | AIMS 수집 서버의 Loki push 주소 |
| `AIMS_TARGETS_URL` | AIMS 백엔드의 수집 대상 목록 API 주소 |

값을 모르면 AIMS 운영자(이노그리드)에게 노드 이름을 알려 주고 받으면 됩니다.

## 3. 설치

1. 이 디렉터리(`config.alloy`, `docker-compose.yml`, `alloy.env.example`, `README.md`)를 노드의 `/opt/aims-alloy/` 에 둡니다.
2. 설정 파일을 만들고 2장의 값을 채웁니다.
   ```bash
   cd /opt/aims-alloy
   cp alloy.env.example alloy.env
   vi alloy.env
   ```
3. 기동합니다.
   ```bash
   docker compose up -d
   ```
4. 상태를 확인합니다. 아래 두 줄이 보이면 정상입니다.
   ```bash
   docker logs aims-alloy 2>&1 | grep -E "discovery.http|tail routine: started|start tailing" | head
   curl -s http://127.0.0.1:12345/-/ready
   ```
   - `discovery.http` 가 주기적으로 돌고 오류가 없으면 백엔드 연결이 맞는 것입니다.
     `404` 가 보이면 `NODE_INSTANCE` 값이 AIMS 에 등록된 주소와 다르거나 AIMS 쪽에 이 노드가 아직 등록되지 않은 것이니 AIMS 운영자에게 알려 주세요.
   - `start tailing file path=/var/log/syslog` 류가 보이면 Loki 전송이 시작된 것입니다.
5. AIMS 운영자에게 완료를 알리면, 포털에서 이 노드의 로그 탭이 켜지고 "에이전트 반영 상태"가 APPLIED 로 바뀐 것을 확인합니다.

## 4. 네트워크

| 방향 | 포트 | 용도 |
|------|------|------|
| 노드 → AIMS 수집 서버 | 3100/tcp | Loki push |
| 노드 → AIMS 백엔드 | 8080/tcp | 수집 대상 목록 조회 |

들어오는 포트는 열 필요가 없습니다. Alloy UI(12345)는 노드 안 `127.0.0.1` 에만 바인딩됩니다.

## 5. 운영

- **업데이트**: 새 `config.alloy` 를 받아 덮어쓰고 `docker compose restart alloy`. `alloy.env` 는 그대로 둡니다.
- **주소 변경**: 노드의 node_exporter 주소가 바뀌면 AIMS 자산관리의 값과 `alloy.env` 의 `NODE_INSTANCE` 를 함께 바꾸고 `docker compose restart alloy`.
- **제거**: `docker compose down -v` (읽기 위치 볼륨까지 지움).
- **읽기 위치**: 컨테이너를 다시 만들어도 `alloy-data` 볼륨에 읽기 위치가 남아 같은 줄을 두 번 보내지 않습니다.
- **자원**: 메모리 100~200MB, CPU 는 로그량에 비례하며 보통 1% 미만입니다.

## 6. Docker 를 쓸 수 없는 노드

Alloy 는 단일 바이너리라 systemd 로도 띄울 수 있습니다. `grafana/alloy` 릴리스의 리눅스 바이너리를 `/usr/local/bin/alloy` 에 두고,
`alloy.env` 를 `EnvironmentFile` 로 읽는 유닛을 만듭니다. 이때 `config.alloy` 의 `/host` 접두어는 필요 없으므로
`discovery.relabel "collect"` 와 `loki.relabel "collect"` 두 블록을 지우고 `loki.source.file "collect"` 의 `targets` 를
`discovery.http.collect.targets` 로 바꿉니다. 필요하면 이 변형본도 AIMS 쪽에서 만들어 드립니다.

## 7. AIMS 쪽 준비 (이노그리드 내부 절차)

노드마다 1회. 자산관리 > 물리 자산에 노드가 등록되어 있고, 그 자산에 node_exporter 수집 대상(`monitoring_target`, address:port)이 연결되어 있어야 합니다.

1. `agent_nodes` 에 `transport='PULL'` 행을 넣습니다(`deploy/sql/2026-10-02_agent_nodes.sql` 의 템플릿). 토큰 발급은 없습니다.
2. 2장의 값 네 개를 설치 담당자에게 전달합니다. `NODE_INSTANCE` 는 그 자산의 node_exporter 타겟 `address:port` 와 글자까지 같아야 합니다.
3. 설치 뒤 `GET /api/v1/monitoring/resource-explorer/node/{nodeId}/services/agent` 가 `transport=PULL`, `targetsStatus=APPLIED` 인지 확인합니다.
   한 번도 가져가지 않았으면 PENDING 과 안내 메시지가 내려옵니다.
4. 목록 API 는 인증이 없으므로 백엔드 8080 은 사내 대역에서만 열려 있어야 합니다. 응답에는 등록된 로그 파일 경로만 있습니다.
