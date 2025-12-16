# AIMS API Gateway

마이크로 데이터센터 연동 스케일아웃 클라우드를 위한 API Gateway

## 프로젝트 개요

aims-cloud의 aims-api-gw 프로젝트는 '마이크로 데이터센터 연동 스케일아웃 클라우드 개발' 사업의 핵심 컴포넌트로, 마이크로데이터센터 클라우드 환경을 관리하기 위한 통합 API 게이트웨이입니다.

## 주요 기능

- **DCIM 데이터 연동**: 데이터센터 인프라 관리 시스템과의 데이터 연동 및 수집
- **인증 및 관리**: 마이크로데이터센터 클라우드 인증 및 관리 기능 연계
- **자원 배포 및 관리**: 마이크로데이터센터에 자원을 배포하고 관리(개발 예정)
- **모니터링**: 실시간 모니터링 데이터 제공(개발 예정)
- **마이그레이션**: 클라우드 간 자원 마이그레이션 지원(개발 예정)

## 기술 스택

- Python 3.12
- FastAPI
- JWT Authentication

## 설치 및 실행

### 요구사항

#### 로컬 실행
- Python 3.12v 이상

#### Docker 실행
- Docker 20.10 이상
- Docker Compose v2.0 이상

### 방법 1: Docker Compose 실행 (권장)

```bash
# Docker Compose로 실행
docker-compose up -d

# 로그 확인
docker-compose logs -f

# 중지
docker-compose down
```

### 방법 2: 로컬 환경 실행

**설치**

```bash
# 가상환경 생성
python -m venv .venv

# 가상환경 활성화 (Linux/Mac)
source .venv/bin/activate

# 가상환경 활성화 (Windows)
.venv\Scripts\activate

# 의존성 설치
pip install -r requirements.txt
```

**환경 설정**

`.env.sample` 파일을 `.env`로 복사하고 필요한 환경변수를 설정하세요.

```bash
cp .env.sample .env
```

**실행**

```bash
# run.py 사용
python run.py

# 또는 uvicorn 직접 실행
uvicorn app.main:app --reload
```

### 접속 정보

API는 `http://localhost:8000`에서 실행됩니다.

- **API 문서 (Swagger)**: `http://localhost:8000/docs`
- **API 문서 (ReDoc)**: `http://localhost:8000/redoc`

## API 엔드포인트

- `GET /` - 기본 상태 확인
- `GET /health` - 환경 구성 검사 포함 상세 헬스체크
- `POST /auth/login` - 사용자 인증 및 JWT 토큰 발급
- `GET /auth/me` - 인증된 사용자 정보 조회
- `POST /openstack/connect` - 오픈스택 인증정보로 연결 검증

### OpenStack 연결 API

```http
POST /openstack/connect
Content-Type: application/json
```

```json
{
  "auth_url": "https://openstack.example.com:5000/v3",
  "username": "demo",
  "password": "secret",
  "project_name": "demo",
  "user_domain_name": "Default",
  "project_domain_name": "Default",
  "region_name": "RegionOne",
  "interface": "public"
}
```

`auth_url`을 생략하면 `.env`에 지정된 `OS_AUTH_URL`이 사용됩니다. 요청이 성공하면 연결 여부, 현재 프로젝트/사용자 ID, 토큰 만료 시간 등의 기본 메타데이터가 반환됩니다.

## 프로젝트 구조

```
aims-api-gw/
├── app/
│   ├── main.py              # FastAPI 애플리케이션 진입점
│   ├── config.py            # 환경 설정
│   ├── auth/
│   │   ├── __init__.py
│   │   ├── jwt.py           # JWT 토큰 처리
│   │   └── routes.py        # 인증 관련 라우트
│   └── routers/
│       ├── __init__.py
│       ├── health.py        # 헬스체크 라우터
│       └── openstack.py     # OpenStack 연결 라우터
├── app/services/
│   ├── __init__.py
│   └── openstack.py         # OpenStack 연결 유틸리티
├── .env                     # 환경변수 (git에서 제외)
├── .env.sample              # 환경변수 샘플
├── requirements.txt         # 의존성 목록
├── run.py                   # 로컬 실행 스크립트
├── Dockerfile               # Docker 이미지 빌드 파일
├── docker-compose.yml       # Docker Compose 설정
├── .dockerignore            # Docker 빌드 제외 파일
├── .gitignore               # Git 제외 파일
├── LICENSE                  # Apache 2.0 라이선스
└── README.md
```

### 환경 변수 예시

```
# Application Configuration
SERVER_HOST=0.0.0.0
SERVER_PORT=8000

# OpenStack 연결 기본값
OS_AUTH_URL=https://openstack.example.com:5000/v3
OS_REGION_NAME=RegionOne
OS_INTERFACE=public

# Logging Configuration
LOG_LEVEL=INFO              # DEBUG, INFO, WARNING, ERROR, CRITICAL
JSON_LOGS=false             # true for production (JSON format), false for development (colored)
LOG_TO_FILE=true            # true to enable file logging
LOG_FILE_PATH=logs/aims-api-gw.log
LOG_FILE_MAX_BYTES=10485760 # 10MB
LOG_FILE_BACKUP_COUNT=5     # Keep 5 backup files
```

## 로깅

애플리케이션은 구조화된 로깅(structlog)을 사용하여 일관된 로그 형식을 제공합니다.

### 로그 출력 방식

로그는 **콘솔**과 **파일** 두 가지 방식으로 출력할 수 있습니다:

#### 1. 콘솔 로깅 (항상 활성화)
- 개발: 컬러로 구분된 사람이 읽기 쉬운 형식
- 프로덕션: JSON 형식

#### 2. 파일 로깅 (선택적)
- 환경변수 `LOG_TO_FILE=true`로 활성화
- 로그 파일 위치: `logs/aims-api-gw.log` (기본값)
- **자동 로테이션**: 10MB마다 새 파일 생성
- **백업 유지**: 최대 5개 백업 파일 보관
- 형식: JSON (프로덕션) 또는 텍스트 (개발)

### 로그 레벨 설정

환경 변수 `LOG_LEVEL`로 로그 레벨을 설정할 수 있습니다:
- `DEBUG`: 상세한 디버깅 정보
- `INFO`: 일반 정보 (기본값)
- `WARNING`: 경고 메시지
- `ERROR`: 에러 메시지
- `CRITICAL`: 치명적 에러

### 로그 설정 예시

```bash
# 개발 환경 (컬러 콘솔 + 텍스트 파일)
LOG_LEVEL=DEBUG
JSON_LOGS=false
LOG_TO_FILE=true
LOG_FILE_PATH=logs/debug.log

# 프로덕션 환경 (JSON 콘솔 + JSON 파일)
LOG_LEVEL=INFO
JSON_LOGS=true
LOG_TO_FILE=true
LOG_FILE_PATH=/var/log/aims-api-gw/app.log
LOG_FILE_MAX_BYTES=52428800  # 50MB
LOG_FILE_BACKUP_COUNT=10

# 로그 파일 비활성화 (콘솔만)
LOG_TO_FILE=false
```

### 로그 파일 로테이션

로그 파일은 설정된 크기에 도달하면 자동으로 로테이션됩니다:

```
logs/
├── aims-api-gw.log         # 현재 로그 파일
├── aims-api-gw.log.1       # 첫 번째 백업
├── aims-api-gw.log.2       # 두 번째 백업
├── aims-api-gw.log.3       # 세 번째 백업
├── aims-api-gw.log.4       # 네 번째 백업
└── aims-api-gw.log.5       # 다섯 번째 백업 (가장 오래된 파일)
```

오래된 백업 파일은 설정된 개수(`LOG_FILE_BACKUP_COUNT`)를 초과하면 자동으로 삭제됩니다.

### 주요 로그 이벤트

- `application_startup`: 애플리케이션 시작
- `login_attempt`, `login_success`, `login_failed`: 인증 이벤트
- `openstack_connect_attempt`, `openstack_connect_success`, `openstack_connect_failed`: OpenStack 연결 이벤트
- `health_check`: 헬스체크 요청

## 개발 조직

aims-cloud

## 라이선스

이 프로젝트는 Apache License 2.0에 따라 라이선스가 부여됩니다. 자세한 내용은 [LICENSE](LICENSE) 파일을 참조하세요.

```
Copyright 2024 aims-cloud

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
