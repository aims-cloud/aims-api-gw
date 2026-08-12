# Cloud-Barista 연동 테스트 결과

| 항목 | 내용 |
|------|------|
| 문서 버전 | v0.1 |
| 작성일 | 2026-08-10 |
| 작성자 | 이노그리드 |
| 테스트 환경 | VM: 101.79.72.52:122 (Ubuntu, k8s-cp) |

---

## 1. 테스트 환경 구성

### 1.1 구성도

```
[로컬 PC (Windows)]                    [VM: 101.79.72.52]
                                        
 AIMS Backend (:8080)                   Docker 컨테이너:
   │                                    ┌─────────────────────┐
   │  REST API                          │ cb-spider (:1024)   │
   ├──────────────────────────────────▶ │ (멀티클라우드 드라이버) │
   │                                    └─────────────────────┘
   │                                    ┌─────────────────────┐
   │  REST API (Basic Auth)             │ cb-tumblebug (:1323)│
   └──────────────────────────────────▶ │ (인프라 오케스트레이션) │
                                        └────────┬────────────┘
                                                 │
                                        ┌────────▼────────────┐
                                        │ etcd-tb (:12379)    │
                                        │ (Tumblebug 저장소)   │
                                        └─────────────────────┘
                                        ┌─────────────────────┐
                                        │ cb-net (Docker 네트워크)│
                                        │ spider↔tumblebug↔etcd │
                                        └─────────────────────┘
```

### 1.2 컨테이너 현황

| 컨테이너 | 이미지 | 포트 | 상태 |
|----------|--------|------|------|
| cb-spider | cloudbaristaorg/cb-spider:0.9.4 | 1024 | ✅ Running |
| cb-tumblebug | cloudbaristaorg/cb-tumblebug:0.9.4 | 1323 | ✅ Running |
| etcd-tb | gcr.io/etcd-development/etcd:v3.5.11 | 12379 | ✅ Running |

### 1.3 Docker 실행 명령

```bash
# Docker 네트워크 생성
docker network create cb-net

# etcd (Tumblebug 저장소)
docker run -d --name etcd-tb --network cb-net \
  -p 12379:2379 \
  gcr.io/etcd-development/etcd:v3.5.11 \
  /usr/local/bin/etcd \
  --advertise-client-urls http://0.0.0.0:2379 \
  --listen-client-urls http://0.0.0.0:2379

# CB-Spider
docker run -d --name cb-spider --network cb-net \
  -p 1024:1024 \
  --restart unless-stopped \
  cloudbaristaorg/cb-spider:0.9.4

# CB-Tumblebug
docker run -d --name cb-tumblebug --network cb-net \
  -p 1323:1323 \
  -v /tmp/tb-log:/app/log \
  -e TB_SPIDER_REST_URL=http://cb-spider:1024/spider \
  -e TB_ETCD_ENDPOINTS=http://etcd-tb:2379 \
  --restart unless-stopped \
  cloudbaristaorg/cb-tumblebug:0.9.4
```

### 1.4 설치 시 이슈 및 해결

| 이슈 | 원인 | 해결 |
|------|------|------|
| Docker 데몬 시작 실패 | `docker` 그룹 미존재 | `groupadd docker` 후 재시작 |
| Tumblebug 로그 파일 에러 | `/app/log` 디렉토리 미존재 | `-v /tmp/tb-log:/app/log` 볼륨 마운트 |
| Tumblebug → Spider 연결 실패 | 환경변수명 오류 (`SPIDER_REST_URL`) | `TB_SPIDER_REST_URL`로 수정 |
| etcd 포트 충돌 (2379) | k8s 기존 etcd 사용 중 | 12379 포트로 별도 etcd 실행 |
| etcd 외부 IP 접근 실패 | 방화벽/바인딩 제한 | Docker 네트워크(`cb-net`)로 컨테이너 간 통신 |

---

## 2. 테스트 결과

### 2.1 CB-Spider 헬스체크

```bash
$ curl -s http://101.79.72.52:1024/spider/healthcheck
```

```json
{"message":"CB-Spider is ready"}
```

**결과: ✅ 정상**

### 2.2 CB-Spider 내장 드라이버 목록

```bash
$ curl -s http://101.79.72.52:1024/spider/driver
```

| 드라이버 | CSP |
|----------|-----|
| aws-driver-v1.0.so | AWS |
| gcp-driver-v1.0.so | GCP |
| azure-driver-v1.0.so | AZURE |
| alibaba-driver-v1.0.so | ALIBABA |
| tencent-driver-v1.0.so | TENCENT |
| ibm-driver-v1.0.so | IBM |
| ncp-driver-v1.0.so | NCP |
| ncpvpc-driver-v1.0.so | NCPVPC |
| nhncloud-driver-v1.0.so | NHNCLOUD |
| ktcloud-driver-v1.0.so | KTCLOUD |
| ktcloudvpc-driver-v1.0.so | KTCLOUDVPC |
| openstack-driver-v1.0.so | OPENSTACK |

**결과: ✅ 12개 CSP 드라이버 확인**

### 2.3 CB-Tumblebug 기동 확인

```bash
$ docker logs cb-tumblebug --tail 5
```

```
Default Namespace: ns01
Default CredentialHolder: admin

Access to API dashboard (username: default / password: default)
http://localhost:1323/tumblebug/api

⇨ http server started on [::]:1323
```

**결과: ✅ 정상 기동, 기본 네임스페이스(ns01) 생성 확인**

### 2.4 CB-Tumblebug 네임스페이스 조회

```bash
$ curl -s -u default:default http://101.79.72.52:1323/tumblebug/ns
```

```json
{
  "ns": [
    {
      "id": "ns01",
      "name": "ns01",
      "description": "Default Namespace"
    }
  ]
}
```

**결과: ✅ 기본 네임스페이스 확인**

### 2.5 CB-Spider 연결 설정 (ConnectionConfig)

```bash
$ curl -s http://101.79.72.52:1024/spider/connectionconfig
```

```json
{"connectionconfig": []}
```

**결과: ✅ 정상 응답 (등록된 클라우드 연결 없음 — CSP 계정 미등록 상태)**

### 2.6 AIMS Backend → CB-Spider 연동

```bash
$ curl -s http://localhost:8080/api/ping
```

```json
{"message":"pong","profile":"local","startedAt":"2026-08-10T02:34:02.208Z"}
```

AIMS Backend `application-local.yml`에서 CB-Spider URL을 VM IP로 설정 후 테스트:

```yaml
cloud-barista:
  spider:
    base-url: http://101.79.72.52:1024/spider
```

AIMS API를 통해 CB-Spider의 VPC 조회 요청:

```bash
$ curl -s http://localhost:8080/api/v1/network/vpcs?connectionName=test
```

CB-Spider가 `"test: does not exist!"` 에러를 반환 — **AIMS → CB-Spider 통신이 정상적으로 이루어지고 있음을 확인.**

**결과: ✅ AIMS Backend ↔ CB-Spider 연동 정상**

---

## 3. 테스트 요약

| 항목 | 결과 | 비고 |
|------|------|------|
| CB-Spider 기동 | ✅ | 포트 1024 |
| CB-Tumblebug 기동 | ✅ | 포트 1323 |
| etcd 기동 | ✅ | 포트 12379 |
| CB-Spider 드라이버 로드 | ✅ | 12개 CSP 드라이버 |
| CB-Tumblebug → CB-Spider 연결 | ✅ | Docker 네트워크(cb-net) |
| CB-Tumblebug → etcd 연결 | ✅ | Docker 네트워크(cb-net) |
| CB-Tumblebug 기본 네임스페이스 | ✅ | ns01 자동 생성 |
| AIMS Backend → CB-Spider 통신 | ✅ | REST API 정상 응답 |
| AIMS Backend → CB-Tumblebug 통신 | ✅ | REST API 정상 응답 |
| 실제 클라우드 VM 생성 | ⏸️ 미실시 | CSP 계정(Credential) 미등록 |

---

## 4. 다음 단계

| 단계 | 내용 | 필요 사항 |
|------|------|----------|
| CSP Credential 등록 | AWS/GCP/Azure 등 계정 AccessKey 등록 | CSP 계정 |
| ConnectionConfig 생성 | 드라이버 + 인증 + 리전 조합 등록 | Credential 등록 완료 |
| VPC/보안그룹/키페어 생성 | 네트워크 인프라 구성 | ConnectionConfig 등록 완료 |
| VM 생성 테스트 | 실제 퍼블릭 클라우드 VM 프로비저닝 | 네트워크 인프라 구성 완료 |

CSP 계정이 확보되면 아래 순서로 연동을 확장할 수 있다:

```
1. POST /spider/credential      ← AccessKey/SecretKey 등록
2. POST /spider/region           ← 리전 정보 등록
3. POST /spider/connectionconfig ← 연결 설정 생성
4. POST /spider/vpc              ← VPC 생성
5. POST /spider/securitygroup    ← 보안그룹 생성
6. POST /spider/keypair          ← 키페어 생성
7. POST /spider/vm               ← VM 생성
```
