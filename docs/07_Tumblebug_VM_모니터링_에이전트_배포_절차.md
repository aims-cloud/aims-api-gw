# Tumblebug VM 모니터링 에이전트 배포 절차

| 항목 | 내용 |
|------|------|
| 문서 버전 | v0.2 |
| 작성일 | 2026-09-07 |
| 작성자 | 양성훈 |
| 상태 | 검증 완료 (테스트베드 1대) |

| 버전 | 일자 | 변경 내용 |
|------|------|-----------|
| v0.2 | 2026-10-06 | §3.4 백엔드 설치 API(node_exporter + Alloy, VM 한 대 `?nodeId=`) 추가, §7 2단계 Node 단위 호출 확정 |
| v0.1 | 2026-09-07 | Tumblebug 로 만든 OpenStack VM 1대에 node_exporter 를 설치하고 tb_* 라벨로 Thanos 까지 연결한 실측 절차 정리 |

---

## 1. 개요

### 1.1 목적

CB-Tumblebug 으로 생성한 VM 이 자원 탐색기 트리의 가상머신 가지에서 **모니터링 항목까지** 보이도록, VM 에 exporter 를 설치하고 Prometheus → Thanos 로 수집하는 절차를 정의한다.
`docs/06_자원_탐색기_트리_설계.md` §9.6(Tumblebug VM 과 Thanos 지표 연결)과 §9.7(Tumblebug 자체 모니터링 API 미사용)의 운영 절차에 해당한다.

### 1.2 왜 이 절차가 필요한가

- Tumblebug 은 VM 의 구조(Namespace → Infra → NodeGroup → Node)만 관리하고 지표는 갖고 있지 않다. 지표는 VM 안의 exporter 가 노출하고 Prometheus 가 끌어가서 Thanos 에 넣는다.
- 백엔드는 Tumblebug VM 을 IP 가 아니라 **스크랩 타겟 라벨 `tb_ns / tb_infra / tb_node`** 로 Thanos 시계열과 조인한다(§9.3 "IP 를 식별 키로 쓰지 않는다"). 이 라벨이 없으면 트리의 VM 노드는 `monitoringStatus: NOT_CONFIGURED` 로 남고 모니터링 항목이 생기지 않는다.
- 라벨은 exporter 가 붙이는 것이 아니라 **Prometheus 스크랩 설정이 타겟 단위로 붙인다.** 따라서 절차의 핵심은 "VM 에 exporter 설치" + "Prometheus 타겟에 라벨 3개 등록" 두 가지다.

### 1.3 향후 VM 배포 기능과의 관계

VM 배포 기능(Tumblebug Infra 생성)을 구현할 때 이 문서의 §3~§4 를 **배포 후처리 단계로 자동화**해야 한다. §7 에 자동화 시 필요한 입력·호출·검증 항목을 정리했다.

---

## 2. 검증 환경

| 구성요소 | 위치 | 비고 |
|----------|------|------|
| CB-Tumblebug 0.13.2 / CB-Spider 0.13.2 | `cloud-barista-aims-cloud---spider-tumblebug` (10.0.0.45), Docker | REST `:1323/tumblebug`, Basic Auth `default/default` |
| Prometheus | `k8s-cp` 호스트 프로세스 | 설정 `/home/ubuntu/opt/monitoring/prometheus.yml`, `--web.enable-lifecycle` (리로드 API 사용 가능), `remote_write` → Thanos Receive |
| Thanos Receive / Query / Store v0.37.2 | `k8s-cp`, Docker | Receive remote_write `:19291`, Query HTTP `:19192`, 라벨 `receive_tenant="aims"` 자동 부착 |
| 대상 VM | Namespace `openstack` / Infra `openstack-infra-01` / NodeGroup `openstack-nodegroup-01` / Node `openstack-nodegroup-01-1` | Ubuntu 24.04, m1.small(vCPU 1, 2GiB), publicIP `101.79.72.62`, privateIP `10.18.11.250`, SSH 사용자 `cb-user` |
| AIMS Backend (로컬) | 개발 PC | `CB_TUMBLEBUG_URL=http://localhost:1323/tumblebug`, `METRICS_QUERY_URL=http://localhost:19192` — 두 포트 모두 SSH 포트포워딩 |

Tumblebug 서버에는 Prometheus 가 없다. **Prometheus 는 Thanos 서버(k8s-cp)에 있다.**

---

## 3. VM 에 node_exporter 설치

### 3.1 Tumblebug 원격 명령 API 로 설치 (권장)

SSH 키를 따로 꺼내지 않고 Infra 안 모든 Node 에 한 번에 실행할 수 있다. `docs/03` 의 node_exporter 절차에 `--collector.systemd` 만 추가했다(트리의 Service Status 항목이 systemd 유닛 상태를 읽는다).

```bash
curl -s -u default:default -X POST "http://localhost:1323/tumblebug/ns/openstack/cmd/infra/openstack-infra-01" \
  -H "Content-Type: application/json" \
  -d '{"command": [
    "cd /tmp && wget -q https://github.com/prometheus/node_exporter/releases/download/v1.9.1/node_exporter-1.9.1.linux-amd64.tar.gz",
    "cd /tmp && tar xzf node_exporter-1.9.1.linux-amd64.tar.gz && sudo cp node_exporter-1.9.1.linux-amd64/node_exporter /usr/local/bin/",
    "sudo useradd --system --no-create-home --shell /usr/sbin/nologin node_exporter || true",
    "printf \"[Unit]\\nDescription=Prometheus Node Exporter\\nAfter=network.target\\n\\n[Service]\\nUser=node_exporter\\nGroup=node_exporter\\nType=simple\\nExecStart=/usr/local/bin/node_exporter --collector.cpu --collector.meminfo --collector.diskstats --collector.filesystem --collector.netdev --collector.loadavg --collector.processes --collector.systemd --web.listen-address=:9100\\n\\n[Install]\\nWantedBy=multi-user.target\\n\" | sudo tee /etc/systemd/system/node_exporter.service > /dev/null",
    "sudo systemctl daemon-reload && sudo systemctl enable --now node_exporter",
    "sleep 2 && curl -s http://localhost:9100/metrics | grep -c node_cpu_seconds_total"
  ]}'
```

성공 판정: 응답 `results[].error` 가 빈 문자열이고, 마지막 명령 `stdout["5"]` 가 0 보다 큰 숫자(예: `"10\n"`).

**`stderr` 에 나오는 아래 메시지는 실패가 아니다.** Tumblebug 이 붙인 hostname(`tb8sursfo0ia00o3c8s1` 형식)이 `/etc/hosts` 에 없어서 sudo 가 내는 경고이며 명령은 정상 실행된다.

```
sudo: unable to resolve host tb8sursfo0ia00o3c8s1: Name or service not known
```

정리하려면 다음 명령을 같은 API 로 한 번 실행한다.

```bash
echo "127.0.1.1 $(hostname)" | sudo tee -a /etc/hosts > /dev/null
```

### 3.2 전제 조건: Tumblebug 이 기록한 publicIP 로 SSH 가 되어야 한다

원격 명령은 Tumblebug 이 VM 생성 시점에 저장한 `publicIP:22` 로 접속한다(로그: `Self-bastion detected — will connect directly`). **floating IP 를 떼어낸 상태에서는 다음처럼 실패한다.**

```
failed to connect directly to target Node "openstack-nodegroup-01-1" at 101.79.72.62:22 (as "cb-user") after 3 attempts (self-bastion, no jump): context deadline exceeded
```

- 같은 floating IP 를 다시 붙이면 바로 된다: `openstack server add floating ip <server> 101.79.72.62`
- **다른 IP 를 붙이면** Tumblebug 의 publicIP 기록과 달라져 계속 옛 주소로 시도한다. 이 경우 Tumblebug 을 거치지 말고 SSH 로 직접 설치한다. 키는 아래로 꺼낸다.

```bash
curl -s -u default:default "http://localhost:1323/tumblebug/ns/openstack/infra/openstack-infra-01?option=accessinfo&accessInfoOption=showSshKey" \
  | jq -r '.infraNodeGroupAccessInfo[].infraNodeAccessInfo[] | {nodeId, publicIP, nodeUserName, privateKey}'
```

### 3.3 방화벽

Prometheus 서버(k8s-cp, VM 으로 나가는 출발 IP `10.0.0.179`)에서 VM `:9100` 인바운드가 허용되어야 한다. 테스트베드에서는 Tumblebug 이 만든 보안 그룹 `openstack-infra-01-openstack-nodegroup-01` 이 추가 규칙 없이 통과했다(개방 상태로 추정). 운영에서는 다음처럼 출발 IP 로 좁힌다.

```bash
openstack security group rule create --proto tcp --dst-port 9100 --remote-ip <prometheus-ip>/32 openstack-infra-01-openstack-nodegroup-01
```

Prometheus 서버의 실제 출발 IP 는 `ip route get <VM IP>` 의 `src` 값이다.

---

### 3.4 백엔드 설치 API 로 설치 (2026-10-06, 로그 수집기 포함)

§3.1 을 손으로 부르는 대신 백엔드가 같은 원격 명령 API 를 **VM 한 대에만**(`POST /ns/{ns}/cmd/infra/{infra}?nodeId={node}`) 보낸다.
node_exporter(§3.1 과 같은 옵션) 와 로그 수집기 Grafana Alloy(systemd `aims-alloy`)를 함께 설치하며, 계약과 동작은 docs/11 §5.5.

```bash
curl -s -X POST -H "Authorization: Bearer <토큰>"   "http://localhost:8080/api/v1/monitoring/resource-explorer/vm/services/agent/install?nsId=openstack&infraId=openstack-infra-01&vmId=openstack-nodegroup-01-1"
# 진행·결과
curl -s -H "Authorization: Bearer <토큰>"   "http://localhost:8080/api/v1/monitoring/resource-explorer/vm/services/agent/install?nsId=openstack&infraId=openstack-infra-01&vmId=openstack-nodegroup-01-1"
```

- 백엔드 설정 `AIMS_AGENT_VM_LOKI_PUSH_URL`, `AIMS_AGENT_VM_TARGETS_URL` 은 VM 에서 보이는 주소여야 한다(비면 503).
- §3.2 전제(Tumblebug 이 기록한 publicIP 로 SSH)는 그대로다. 실패하면 응답의 `installStep=SEND_COMMAND`, `installError` 에 Tumblebug 오류가 남는다.
- 실측(2026-10-06, 위 VM): 약 1분 뒤 INSTALLED. 기설치 node_exporter 1.9.1 은 내려받지 않고 유닛만 다시 썼고 9100 수집은 이어졌다(docs/11 §8.3).
- 스크랩 타겟 등록(§4)은 이 API 가 하지 않는다.

## 4. Prometheus 스크랩 타겟 등록 (라벨 부착)

### 4.1 설정 추가

`k8s-cp` 의 `/home/ubuntu/opt/monitoring/prometheus.yml` 의 `scrape_configs` 에 추가한다. **라벨 값 3개는 Tumblebug 의 nsId, infraId, nodeId 와 글자 하나까지 같아야** 트리와 조인된다.

```yaml
  - job_name: tb-vms
    static_configs:
      - targets: ["101.79.72.62:9100"]
        labels:
          tb_ns: openstack
          tb_infra: openstack-infra-01
          tb_node: openstack-nodegroup-01-1
```

- `instance` 라벨은 건드리지 않는다(기본값 `address:port`).
- `scrape_interval` 은 30초 이하를 유지한다. 백엔드의 rate 창(5분)과 존재 판정 창(24시간)이 이를 전제로 한다.
- 이미 있던 `remote_write: url: http://localhost:19291/api/v1/receive` 그대로 사용한다.

### 4.2 검증·리로드

```bash
/usr/local/bin/promtool check config /home/ubuntu/opt/monitoring/prometheus.yml
curl -s -X POST http://localhost:9090/-/reload && echo reloaded
```

### 4.3 타겟 상태 확인

```bash
curl -s http://localhost:9090/api/v1/targets | python3 -c "import sys,json; [print(t['labels'].get('job'), t['scrapeUrl'], t['health'], t.get('lastError','')) for t in json.load(sys.stdin)['data']['activeTargets']]"
```

기대: `tb-vms http://101.79.72.62:9100/metrics up`. `down` 이면 `lastError` 로 원인을 본다(연결 거부·타임아웃이면 §3.3).

---

## 5. Thanos 확인

```bash
curl -s "http://localhost:19192/api/v1/query" --data-urlencode 'query=up{tb_node="openstack-nodegroup-01-1"}' | python3 -m json.tool
```

실측 결과(발췌). `tb_*` 세 라벨이 트리의 `resourceId` `TB:openstack/openstack-infra-01/openstack-nodegroup-01-1` 과 1:1 로 대응한다. Receive 가 붙이는 `receive_tenant`, `tenant_id` 는 백엔드가 보지 않는다.

```json
{
  "metric": {
    "__name__": "up",
    "instance": "101.79.72.62:9100",
    "job": "tb-vms",
    "receive_replica": "0",
    "receive_tenant": "aims",
    "tb_infra": "openstack-infra-01",
    "tb_node": "openstack-nodegroup-01-1",
    "tb_ns": "openstack",
    "tenant_id": "default-tenant"
  },
  "value": [1788744691.216, "1"]
}
```

백엔드가 쓰는 판정 쿼리도 같이 확인할 수 있다.

```
max by (tb_ns, tb_infra, tb_node) (count_over_time(up{tb_node!=""}[24h]))   -- 등록 여부(존재)
max by (tb_ns, tb_infra, tb_node) (up{tb_node!=""})                          -- 현재 수집 상태
```

---

## 6. 백엔드 확인

### 6.1 선행 조건

- `role_namespaces` 에 토큰 역할 ↔ Namespace 매핑이 있어야 트리에 가상머신 가지가 생긴다.

```sql
INSERT INTO role_namespaces (role_id, ns_id, created_at)
VALUES ((SELECT id FROM roles WHERE name = 'ADMIN'), 'openstack', now());
```

- 로컬 백엔드는 Tumblebug(1323)과 Thanos Query(19192) 두 포트에 포트포워딩이 필요하다.

### 6.2 트리

```bash
curl -s -H "Authorization: Bearer <토큰>" "http://localhost:8080/api/v1/monitoring/resource-explorer/tree" \
  | jq '.. | objects | select(.type? == "VIRTUAL_MACHINE") | {name, status, monitoringStatus, items: [.children[].name]}'
```

기대 결과. `monitoringStatus` 가 `NOT_CONFIGURED` → `COLLECTING` 으로 바뀌고 node_exporter 항목 8개가 붙는다. 스냅샷 캐시가 1분이라 설정 직후 첫 응답은 1분까지 이전 값일 수 있다.

```json
{
  "name": "openstack-nodegroup-01-1",
  "status": "Running",
  "monitoringStatus": "COLLECTING",
  "items": ["CPU", "Memory", "Disk", "Network", "Process", "Service Status", "Swap", "System"]
}
```

### 6.3 NodeGroup 패널

```bash
curl -s -H "Authorization: Bearer <토큰>" "http://localhost:8080/api/v1/monitoring/resource-explorer/node-group/vms?nsId=openstack&infraId=openstack-infra-01&nodeGroupId=openstack-nodegroup-01"
```

VM 행에 `tumblebugStatus`, `provider`, `ipAddresses` 와 함께 CPU·Memory·File System 사용률(최근 10분 평균)이 나온다.

---

## 7. VM 배포 기능 구현 시 자동화 요건

VM 배포(Infra 생성) 기능은 아래를 **배포 후처리**로 수행해야 트리·패널에 모니터링이 바로 붙는다.

| 단계 | 입력 | 호출 | 성공 판정 |
|------|------|------|-----------|
| 1. VM 준비 확인 | nsId, infraId | `GET /ns/{ns}/infra/{infra}?option=status` — Node `status == Running`, `publicIP` 존재 | 모든 Node Running |
| 2. exporter 설치 | nsId, infraId, nodeId | 백엔드 설치 API(§3.4) — 내부적으로 `POST /ns/{ns}/cmd/infra/{infra}?nodeId={node}` | 설치 상태 INSTALLED |
| 3. 스크랩 타겟 등록 | Node 별 `publicIP`(또는 내부망이면 `privateIP`), nsId, infraId, nodeId | Prometheus 에 타겟 추가 + 라벨 `tb_ns/tb_infra/tb_node` | `/api/v1/targets` 에서 `up` |
| 4. Thanos 확인 | nsId, infraId, nodeId | `up{tb_ns=...,tb_infra=...,tb_node=...} == 1` | 값 1 |
| 5. 백엔드 캐시 | — | 필요 시 `MonitoringCapabilityResolver.evict()`, `TumblebugTreeBuilder.evict()` 호출로 즉시 반영 | 트리 `monitoringStatus: COLLECTING` |

**3단계 구현 방식 권장**: `prometheus.yml` 을 직접 수정하지 말고 `file_sd_configs` 로 디렉터리(예: `/etc/prometheus/targets/tb-vms/*.json`)를 바라보게 하고, 배포 기능이 VM 마다 JSON 파일을 쓰게 한다. Prometheus 가 파일 변경을 자동 감지하므로 리로드가 필요 없고, VM 삭제 시 파일만 지우면 된다.

```json
[
  {
    "targets": ["101.79.72.62:9100"],
    "labels": {
      "tb_ns": "openstack",
      "tb_infra": "openstack-infra-01",
      "tb_node": "openstack-nodegroup-01-1"
    }
  }
]
```

```yaml
  - job_name: tb-vms
    file_sd_configs:
      - files: ["/etc/prometheus/targets/tb-vms/*.json"]
        refresh_interval: 30s
```

이 경우 백엔드(또는 배포 에이전트)가 Prometheus 서버의 디렉터리에 파일을 쓸 수 있어야 한다(SSH/SCP 또는 공유 볼륨). 대안으로 Prometheus HTTP SD 를 백엔드가 직접 서빙하는 방식도 가능하며, 그때는 백엔드가 `role_namespaces`·Tumblebug 스냅샷에서 타겟 목록을 만들어 내려준다.

### 7.1 주의사항

- **publicIP 변동**: floating IP 를 바꾸면 Tumblebug 원격 명령(§3.2)과 스크랩 타겟(§4)이 모두 옛 주소를 가리킨다. 배포 기능은 IP 변경 시 타겟 파일을 갱신해야 한다. 백엔드 조인은 라벨 기반이라 영향이 없다.
- **Tumblebug rate limit**: Tumblebug 은 짧은 시간에 연속 요청이 들어오면 `429 rate limit exceeded` 를 낸다(4요청/20ms 에서 발생). 백엔드 트리 조회는 매핑된 Namespace 만 조회하고 429 시 재시도하도록 되어 있지만, 배포 기능이 Tumblebug API 를 연속 호출할 때도 같은 대비가 필요하다.
- **GPU VM**: dcgm_exporter 를 VM 에 설치하는 경우 같은 타겟 라벨(`tb_*`)로 `:9400` 을 등록하면 트리의 GPU 그룹·VRAM 카드가 채워진다. 백엔드는 node_exporter 와 dcgm_exporter 에 같은 선택자를 쓴다.
- **물리 호스트 하위 가상 자원과 구분**: 이 절차는 Tumblebug 가지 전용이다. 물리 호스트 하위 가상 자원(CB-Spider, `SPIDER:{connectionName}/{systemId}`, docs/06 §9.3)은 라벨 규약이 다르다(미확정 — 1세부 데이터 확정 시 재작업).

---

## 8. 관련 코드

| 역할 | 위치 |
|------|------|
| tb_* 라벨 선택자 | `monitoring/service/resourceexplorer/tree/MetricSelector.java` |
| 존재·상태 판정 (24h/up) | `monitoring/service/resourceexplorer/tree/MonitoringCapabilityResolver.java` |
| Tumblebug 구조 조회·캐시·429 재시도 | `monitoring/service/resourceexplorer/tree/TumblebugTreeBuilder.java` |
| VM 사용률(목록·Top-N) | `monitoring/service/resourceexplorer/tree/HostMetricsQueryService.java` `utilizationByTumblebugNode` |
| NodeGroup 패널 | `monitoring/controller/resourceexplorer/vm/ResourceExplorerNodeGroupController.java` |
| Tumblebug REST 클라이언트 (`sendCommand` 포함) | `cloudbarista/tumblebug/TumblebugClient.java` |
