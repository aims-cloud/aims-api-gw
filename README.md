# AIMS Cloud Backend

마이크로 데이터센터(MDC) 및 퍼블릭 클라우드 통합 관리 포탈의 백엔드 API 서버.

## 기술 스택

| 항목 | 버전 |
|------|------|
| Java | 17 |
| Spring Boot | 4.1.0 |
| Gradle | 9.5.1 (Kotlin DSL, Wrapper 포함) |
| DB (운영) | PostgreSQL |
| DB (로컬) | H2 (인메모리) |
| 퍼블릭 클라우드 연동 | [Cloud-Barista](https://github.com/cloud-barista) (CB-Spider, CB-Tumblebug) |

## 프로젝트 구조

```
com.innogrid.aims
├── config/                 # Security, RestClient 등 전역 설정
├── common/                 # 공통 응답 DTO, 예외 처리
├── cloudbarista/           # Cloud-Barista 연동 클라이언트
│   ├── spider/             #   CB-Spider (멀티클라우드 드라이버 API)
│   └── tumblebug/          #   CB-Tumblebug (인프라 오케스트레이션 API)
├── auth/                   # 계정 및 권한 관리
├── metering/               # 리소스 사용량, 보고서, 가격 정책
├── billing/                # 청구 금액, 이용 현황, 최적화 진단
├── resource/               # 가상자원 관리 (VM, 스냅샷, 카탈로그, 이미지, 컴퓨팅)
├── infra/                  # 인프라 관리 (스토리지, 네트워크, 자산, IP, 랙)
├── dashboard/              # 대시보드 (토폴로지, 노드, GPU, 인프라 등)
└── monitoring/             # 모니터링 (이벤트, 알람, 로그, 에이전트, 물리자산)
```

## 아키텍처

```
┌─────────────────────────────────────────────────────┐
│                  AIMS Cloud Frontend                │
└────────────────────────┬────────────────────────────┘
                         │ REST API
┌────────────────────────▼────────────────────────────┐
│               AIMS Cloud Backend                    │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐            │
│  │   auth   │ │ billing  │ │dashboard │  ...        │
│  └──────────┘ └──────────┘ └──────────┘            │
│  ┌─────────────────────────────────────┐            │
│  │         cloudbarista client         │            │
│  └──────────┬──────────────┬───────────┘            │
└─────────────┼──────────────┼────────────────────────┘
              │              │
   ┌──────────▼──┐    ┌──────▼───────┐
   │  CB-Spider  │    │ CB-Tumblebug │
   │ (드라이버)  │◄───│ (오케스트레이션)│
   └──────┬──────┘    └──────────────┘
          │
  ┌───────┼───────────────────┐
  │       │                   │
┌─▼──┐ ┌─▼──┐ ┌─────┐ ┌─────▼┐
│AWS │ │GCP │ │Azure│ │NCP...│
└────┘ └────┘ └─────┘ └──────┘
```

**퍼블릭 클라우드** 자원(VM, VPC, 디스크 등)은 Cloud-Barista에 위임하고,
**MDC 자체 자원**(물리 서버, IPMI/SNMP, 랙 등)과 **비즈니스 로직**(인증, 빌링, 모니터링)은 자체 구현한다.

## 빌드 및 실행

```bash
# 테스트
./gradlew test

# 로컬 실행 (H2 인메모리 DB, local 프로파일 자동 적용)
./gradlew bootRun

# fat jar 빌드
./gradlew bootJar    # → build/libs/app.jar
```

## 환경 설정

모든 외부 서비스 주소는 환경변수로 주입한다. 로컬 개발 시에는 `application-local.yml`의 기본값이 적용되므로 별도 설정 없이 실행 가능하다.

| 환경변수 | 설명 | 로컬 기본값 |
|----------|------|-------------|
| `AIMS_DB_URL` | DB 접속 URL | `jdbc:h2:mem:aims` |
| `AIMS_DB_USER` | DB 사용자 | `sa` |
| `AIMS_DB_PASSWORD` | DB 비밀번호 | (빈 값) |
| `CB_SPIDER_URL` | CB-Spider API URL | `http://localhost:1024/spider` |
| `CB_TUMBLEBUG_URL` | CB-Tumblebug API URL | `http://localhost:1323/tumblebug` |
| `CB_TUMBLEBUG_USER` | CB-Tumblebug 인증 사용자 | `default` |
| `CB_TUMBLEBUG_PASS` | CB-Tumblebug 인증 비밀번호 | `default` |

## 브랜치 전략

```
main                  ← 안정 릴리스
 └─ develop           ← 통합 브랜치 (기능 머지 대상)
     ├─ feat/auth     ← 기능 개발
     ├─ feat/billing
     ├─ fix/xxx       ← 버그 수정
     └─ docs/xxx      ← 문서
```

## API 경로 요약

| 경로 | 설명 |
|------|------|
| `/api/ping` | 헬스체크 |
| `/api/v1/auth/**` | 인증 (로그인, 비밀번호 초기화) |
| `/api/v1/users/**` | 사용자 관리 |
| `/api/v1/roles/**` | 역할/권한 관리 |
| `/api/v1/metering/**` | 미터링 |
| `/api/v1/billing/**` | 빌링 |
| `/api/v1/optimization/**` | 비용/자원 최적화 진단 |
| `/api/v1/vms/**` | VM 라이프사이클 |
| `/api/v1/snapshots/**` | 스냅샷/백업 |
| `/api/v1/catalogs/**` | 카탈로그 (자원 요청/승인) |
| `/api/v1/images/**` | 이미지/템플릿 |
| `/api/v1/tags/**` | 태깅 |
| `/api/v1/compute/**` | 컴퓨팅 관리 |
| `/api/v1/storage/**` | 스토리지 관리 |
| `/api/v1/network/**` | 네트워크 관리 |
| `/api/v1/assets/**` | 물리/가상 자산 관리 |
| `/api/v1/ips/**` | IP 관리 |
| `/api/v1/racks/**` | 랙실장도 |
| `/api/v1/dashboards/**` | 대시보드 |
| `/api/v1/monitoring/**` | 모니터링 (리소스, 이벤트, 알람, 로그, 에이전트, 물리자산) |

## 라이선스

Apache License 2.0
