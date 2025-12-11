# AIMS API Gateway

aims-api-gw는 aims-cloud 조직이 주관하는 공개SW 프로젝트로, '마이크로 데이터센터 연동 스케일아웃 클라우드 개발' 사업을 위한 API 게이트웨이를 제공합니다. 마이크로데이터센터 클라우드의 핵심 기능(인증, 자원 관리, 모니터링, 마이그레이션 등)을 단일 인터페이스에서 다룰 수 있도록 FastAPI 기반으로 설계되었습니다.

## 프로젝트 개요

- **목표**: 마이크로데이터센터 연동 클라우드 환경을 위한 경량 API 게이트웨이 틀을 마련하고 점진적으로 기능을 확장합니다.
- **1차 범위**: FastAPI 초기 골격, 헬로 엔드포인트, 라우터 구조, JWT 기반 인증, `.env` 구성.

## 향후 제공 기능

- **DCIM 데이터 연동 및 수집**: DCIM 시스템으로부터 설비 정보를 수집하고 연동.
- **클라우드 인증 및 관리**: 마이크로데이터센터 클라우드 사용자/자격 증명 및 연동 기능.
- **자원 배포/관리**: 워크로드 배포, 정책 기반 자원 할당 및 수명주기 관리.
- **모니터링 데이터 제공**: 실시간/히스토릭 자원 상태 및 지표 제공.
- **마이그레이션 지원**: 이기종 클라우드 간 워크로드 마이그레이션 지원.

## 기술 스택

- Python 3.12
- FastAPI
- JWT Authentication (python-jose)
- Pydantic Settings

## 설치 및 실행

### 요구사항

- Python 3.12 이상

### 설치

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

### 환경 설정

`.env.sample`을 복사해 프로젝트 환경을 정의합니다.

```bash
cp .env.sample .env
```

필요 시 `APP_NAME`, `APP_VERSION`, `SECRET_KEY`, `ACCESS_TOKEN_EXPIRE_MINUTES` 등을 수정합니다.

### 실행

**방법 1: run.py 사용 (권장)**
```bash
python run.py
```

**방법 2: uvicorn 직접 실행**
```bash
uvicorn app.main:app --reload
```

API는 `http://localhost:8000`에서, 자동 문서는 `http://localhost:8000/docs`에서 확인합니다.

## API 엔드포인트 (1차 버전)

- `GET /` - Health check 및 기본 정보
- `GET /hello/` - 헬로 엔드포인트(라우터 예시)
- `POST /auth/login` - 사용자 인증 및 JWT 토큰 발급
- `GET /auth/me` - 인증된 사용자 정보 조회

## 라우팅 구조

```
aims-api-gw/
├── app/
│   ├── main.py                  # FastAPI 앱 엔트리 포인트
│   ├── config.py                # 환경 설정 로딩
│   ├── auth/                    # JWT 인증 모듈
│   │   ├── jwt.py               # 토큰 발행/검증
│   │   └── routes.py            # 인증 라우터
│   └── routers/
│       ├── hello.py             # Hello API 라우터
│       └── __init__.py          # 기능별 라우터 묶음
├── .env                         # 실행 환경 변수 (git ignore)
├── .env.sample                  # 환경 변수 샘플
├── requirements.txt             # 의존성 목록
└── README.md
```

필요한 기능별 라우터를 `app/routers` 아래에 추가하면서 `app/routers/__init__.py`에서 묶어 FastAPI 인스턴스에 포함하면 됩니다.

## 개발 조직

- aims-cloud

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
