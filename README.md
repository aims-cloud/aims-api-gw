# AIMS API Gateway

마이크로 데이터센터 연동 스케일아웃 클라우드를 위한 API 게이트웨이

## 프로젝트 개요

aims-api-gw는 '마이크로 데이터센터 연동 스케일아웃 클라우드 개발' 사업의 핵심 컴포넌트로, 마이크로데이터센터 클라우드 환경을 관리하기 위한 통합 API 게이트웨이입니다.

## 주요 기능

- **DCIM 데이터 연동**: 데이터센터 인프라 관리 시스템과의 데이터 연동 및 수집
- **인증 및 관리**: 마이크로데이터센터 클라우드 인증 및 관리 기능 연계
- **자원 배포 및 관리**: 마이크로데이터센터에 자원을 배포하고 관리
- **모니터링**: 실시간 모니터링 데이터 제공
- **마이그레이션**: 클라우드 간 자원 마이그레이션 지원

## 기술 스택

- Python 3.12
- FastAPI
- JWT Authentication

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

`.env.sample` 파일을 `.env`로 복사하고 필요한 환경변수를 설정하세요.

```bash
cp .env.sample .env
```

### 실행

```bash
uvicorn app.main:app --reload
```

API는 `http://localhost:8000`에서 실행됩니다.

API 문서는 `http://localhost:8000/docs`에서 확인할 수 있습니다.

## API 엔드포인트

- `GET /` - Health check 및 기본 정보
- `POST /auth/login` - 사용자 인증 및 JWT 토큰 발급
- `GET /auth/me` - 인증된 사용자 정보 조회

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
│       └── __init__.py
├── .env                     # 환경변수 (git에서 제외)
├── .env.sample             # 환경변수 샘플
├── requirements.txt        # 의존성 목록
└── README.md
```

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
