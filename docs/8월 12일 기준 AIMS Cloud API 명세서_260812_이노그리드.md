# AIMS Cloud Backend API 명세서

| 항목 | 내용 |
|------|------|
| 문서 버전 | v0.1 |
| 작성일 | 2026-08-12 |
| 작성자 | 이노그리드 |
| API 총 수 | 129개 |
| 카테고리 | 24개 |
| Base URL | `http://{서버주소}:8080` |
| Swagger UI | `http://{서버주소}:8080/swagger-ui/index.html` |
| OpenAPI JSON | `http://{서버주소}:8080/v3/api-docs` |

---

## 인증 방식

모든 API는 JWT 인증이 필요하며, 아래 API는 인증 없이 접근 가능합니다.

| 인증 불필요 경로 |
|----------------|
| `POST /api/v1/auth/login` |
| `POST /api/v1/auth/register` |
| `POST /api/v1/auth/validate` |
| `POST /api/v1/auth/refresh` |
| `POST /api/v1/auth/validate-key` |
| `GET /api/ping` |
| `GET /swagger-ui/**` |
| `GET /actuator/**` |

**인증 절차:**
1. `POST /api/v1/auth/login` 호출 → accessToken 발급
2. 이후 요청 헤더에 `Authorization: Bearer {accessToken}` 포함

---

## 1. 인증 (5개)

| Method | URL | 설명 | Request Body |
|--------|-----|------|:---:|
| POST | `/api/v1/auth/login` | 로그인 (JWT 발급) | O |
| POST | `/api/v1/auth/register` | 회원가입 | O |
| POST | `/api/v1/auth/validate` | 토큰 검증 (세부1,3 연동용) | O |
| POST | `/api/v1/auth/refresh` | Access Token 갱신 | O |
| POST | `/api/v1/auth/validate-key` | API Key 검증 (서비스 간) | O |

**로그인 요청/응답:**
```json
// POST /api/v1/auth/login
// Request
{"email": "admin@aims.cloud", "password": "<비밀번호>"}
// ※ 실제 비밀번호는 환경변수로 관리

// Response
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOi...",
    "refreshToken": "eyJhbGciOi..."
  }
}
```

**회원가입 요청/응답:**
```json
// POST /api/v1/auth/register
// Request
{
  "email": "hong@innogrid.com",
  "password": "pass1234!",
  "name": "홍길동",
  "organization": "이노그리드"
}

// Response
{
  "success": true,
  "data": {
    "id": 3,
    "email": "hong@innogrid.com",
    "name": "홍길동",
    "organization": "이노그리드",
    "status": "ACTIVE",
    "roles": ["USER"],
    "createdAt": "2026-08-12T10:30:00Z"
  }
}
```

**토큰 갱신 요청/응답:**
```json
// POST /api/v1/auth/refresh
// Request
{"refreshToken": "eyJhbGciOi..."}

// Response
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOi...(new)...",
    "refreshToken": "eyJhbGciOi...(new)..."
  }
}
```

**토큰 검증 응답 (세부1,3 연동):**
```json
// POST /api/v1/auth/validate
{
  "success": true,
  "data": {
    "valid": true,
    "email": "user@innogrid.com",
    "userId": 1,
    "name": "김승한",
    "roles": "ADMIN,USER",
    "organization": "이노그리드"
  }
}
```

**API Key 검증 요청/응답:**
```json
// POST /api/v1/auth/validate-key
// Request
{"apiKey": "sk-aims-abc123def456"}

// Response
{
  "success": true,
  "data": {
    "valid": true,
    "serviceName": "aims-metering-service",
    "permissions": ["METERING_READ", "METERING_WRITE"]
  }
}
```

---

## 2. 사용자 관리 (8개)

| Method | URL | 설명 | 파라미터 |
|--------|-----|------|---------|
| GET | `/api/v1/users` | 사용자 목록 | - |
| POST | `/api/v1/users` | 사용자 생성 (관리자) | Body |
| GET | `/api/v1/users/{userId}` | 사용자 상세 | userId |
| PUT | `/api/v1/users/{userId}` | 사용자 수정 | userId, Body |
| DELETE | `/api/v1/users/{userId}` | 사용자 삭제 | userId |
| PATCH | `/api/v1/users/{userId}/status` | 상태 변경 | userId, status |
| POST | `/api/v1/users/{userId}/roles/{roleId}` | 역할 할당 | userId, roleId |
| DELETE | `/api/v1/users/{userId}/roles/{roleId}` | 역할 해제 | userId, roleId |

**사용자 응답:**
```json
{
  "id": 1,
  "email": "admin@aims.cloud",
  "name": "관리자",
  "organization": "이노그리드",
  "status": "ACTIVE",
  "roles": ["ADMIN", "USER"],
  "createdAt": "2026-08-12T00:00:00Z"
}
```

**사용자 생성 요청:**
```json
// POST /api/v1/users
// Request
{
  "email": "hong@innogrid.com",
  "password": "pass1234!",
  "name": "홍길동",
  "organization": "이노그리드",
  "roles": ["USER"]
}

// Response
{
  "id": 3,
  "email": "hong@innogrid.com",
  "name": "홍길동",
  "organization": "이노그리드",
  "status": "ACTIVE",
  "roles": ["USER"],
  "createdAt": "2026-08-12T10:30:00Z"
}
```

**사용자 수정 요청:**
```json
// PUT /api/v1/users/3
// Request
{
  "name": "홍길동",
  "organization": "이노그리드 클라우드사업부",
  "password": "newPass5678!"
}

// Response
{
  "id": 3,
  "email": "hong@innogrid.com",
  "name": "홍길동",
  "organization": "이노그리드 클라우드사업부",
  "status": "ACTIVE",
  "roles": ["USER"],
  "createdAt": "2026-08-12T10:30:00Z"
}
```

**사용자 삭제 응답:**
```json
// DELETE /api/v1/users/3
// Response
{
  "success": true,
  "message": "사용자가 삭제되었습니다."
}
```

**상태 변경 요청/응답:**
```json
// PATCH /api/v1/users/3/status?status=SUSPENDED
// Response
{
  "id": 3,
  "email": "hong@innogrid.com",
  "name": "홍길동",
  "status": "SUSPENDED"
}
```

**역할 할당 응답:**
```json
// POST /api/v1/users/3/roles/2
// Response
{
  "success": true,
  "message": "역할이 할당되었습니다."
}
```

**역할 해제 응답:**
```json
// DELETE /api/v1/users/3/roles/2
// Response
{
  "success": true,
  "message": "역할이 해제되었습니다."
}
```

---

## 3. 역할/권한 (7개)

| Method | URL | 설명 | 파라미터 |
|--------|-----|------|---------|
| GET | `/api/v1/roles` | 역할 목록 | - |
| POST | `/api/v1/roles` | 역할 생성 | Body |
| GET | `/api/v1/roles/{roleId}` | 역할 상세 | roleId |
| PUT | `/api/v1/roles/{roleId}` | 역할 수정 | roleId, Body |
| DELETE | `/api/v1/roles/{roleId}` | 역할 삭제 | roleId |
| GET | `/api/v1/roles/{roleId}/permissions` | 권한 조회 | roleId |
| POST | `/api/v1/roles/{roleId}/permissions` | 권한 추가 | roleId, Body |

**역할 목록 응답:**
```json
// GET /api/v1/roles
// Response
[
  {"roleId": 1, "name": "ADMIN", "description": "시스템 관리자", "createdAt": "2026-08-01T00:00:00Z"},
  {"roleId": 2, "name": "USER", "description": "일반 사용자", "createdAt": "2026-08-01T00:00:00Z"},
  {"roleId": 3, "name": "OPERATOR", "description": "운영자", "createdAt": "2026-08-05T09:00:00Z"}
]
```

**역할 생성 요청/응답:**
```json
// POST /api/v1/roles
// Request
{
  "name": "OPERATOR",
  "description": "운영자"
}

// Response
{
  "roleId": 3,
  "name": "OPERATOR",
  "description": "운영자",
  "createdAt": "2026-08-12T10:00:00Z"
}
```

**역할 수정 요청:**
```json
// PUT /api/v1/roles/3
// Request
{
  "name": "OPERATOR",
  "description": "MDC 운영 담당자"
}

// Response
{
  "roleId": 3,
  "name": "OPERATOR",
  "description": "MDC 운영 담당자",
  "createdAt": "2026-08-05T09:00:00Z"
}
```

**역할 삭제 응답:**
```json
// DELETE /api/v1/roles/3
// Response
{
  "success": true,
  "message": "역할이 삭제되었습니다."
}
```

**권한 조회 응답:**
```json
// GET /api/v1/roles/1/permissions
// Response
[
  {"permissionId": 1, "resource": "USERS", "action": "READ"},
  {"permissionId": 2, "resource": "USERS", "action": "WRITE"},
  {"permissionId": 3, "resource": "VMS", "action": "READ"},
  {"permissionId": 4, "resource": "VMS", "action": "WRITE"},
  {"permissionId": 5, "resource": "BILLING", "action": "READ"}
]
```

**권한 추가 요청/응답:**
```json
// POST /api/v1/roles/3/permissions
// Request
{
  "resource": "VMS",
  "action": "READ"
}

// Response
{
  "permissionId": 6,
  "resource": "VMS",
  "action": "READ",
  "roleId": 3
}
```

---

## 4. VM 관리 (7개)

| Method | URL | 설명 | 파라미터 |
|--------|-----|------|---------|
| GET | `/api/v1/vms` | VM 목록 | connectionName, nsId |
| POST | `/api/v1/vms` | VM 생성 (CB-Spider) | Body |
| POST | `/api/v1/vms/infra` | 멀티클라우드 인프라 생성 (CB-Tumblebug) | nsId, Body |
| GET | `/api/v1/vms/{vmName}` | VM 상세 | vmName, connectionName |
| GET | `/api/v1/vms/{vmName}/status` | VM 상태 | vmName, connectionName |
| POST | `/api/v1/vms/{vmName}/control` | VM 제어 (start/stop/reboot) | vmName, connectionName, action |
| DELETE | `/api/v1/vms/{vmName}` | VM 삭제 | vmName, connectionName |

**VM 생성 요청:**
```json
{
  "connectionName": "aws-seoul-conn",
  "reqInfo": {
    "name": "aims-vm-01",
    "imageName": "ubuntu-22.04",
    "vpcName": "aims-vpc-01",
    "subnetName": "aims-subnet-01",
    "securityGroupNames": ["aims-sg-01"],
    "vmSpecName": "t3.medium",
    "keyPairName": "aims-keypair-01"
  }
}
```

---

## 5. 네트워크 (13개)

| Method | URL | 설명 | 파라미터 |
|--------|-----|------|---------|
| GET | `/api/v1/network/vpcs` | VPC 목록 | connectionName |
| POST | `/api/v1/network/vpcs` | VPC 생성 | Body |
| GET | `/api/v1/network/vpcs/{vpcName}` | VPC 상세 | vpcName, connectionName |
| DELETE | `/api/v1/network/vpcs/{vpcName}` | VPC 삭제 | vpcName, connectionName |
| POST | `/api/v1/network/vpcs/{vpcName}/subnets` | 서브넷 추가 | vpcName, Body |
| DELETE | `/api/v1/network/vpcs/{vpcName}/subnets/{subnetName}` | 서브넷 삭제 | vpcName, subnetName, connectionName |
| GET | `/api/v1/network/security-groups` | 보안그룹 목록 | connectionName |
| POST | `/api/v1/network/security-groups` | 보안그룹 생성 | Body |
| DELETE | `/api/v1/network/security-groups/{sgName}` | 보안그룹 삭제 | sgName, connectionName |
| POST | `/api/v1/network/security-groups/{sgName}/rules` | 보안규칙 추가 | sgName, Body |
| GET | `/api/v1/network/keypairs` | 키페어 목록 | connectionName |
| POST | `/api/v1/network/keypairs` | 키페어 생성 | Body |
| DELETE | `/api/v1/network/keypairs/{keyPairName}` | 키페어 삭제 | keyPairName, connectionName |

**VPC 생성 요청/응답:**
```json
// POST /api/v1/network/vpcs
// Request
{
  "connectionName": "aws-seoul-conn",
  "reqInfo": {
    "name": "aims-vpc-01",
    "IPv4_CIDR": "10.0.0.0/16",
    "subnetInfoList": [
      {"name": "aims-subnet-01", "IPv4_CIDR": "10.0.1.0/24"}
    ]
  }
}

// Response
{
  "name": "aims-vpc-01",
  "IPv4_CIDR": "10.0.0.0/16",
  "subnetInfoList": [
    {"name": "aims-subnet-01", "IPv4_CIDR": "10.0.1.0/24", "status": "available"}
  ],
  "status": "available"
}
```

**서브넷 추가 요청/응답:**
```json
// POST /api/v1/network/vpcs/aims-vpc-01/subnets
// Request
{
  "connectionName": "aws-seoul-conn",
  "reqInfo": {
    "name": "aims-subnet-02",
    "IPv4_CIDR": "10.0.2.0/24"
  }
}

// Response
{
  "name": "aims-subnet-02",
  "IPv4_CIDR": "10.0.2.0/24",
  "status": "available"
}
```

**보안그룹 생성 요청/응답:**
```json
// POST /api/v1/network/security-groups
// Request
{
  "connectionName": "aws-seoul-conn",
  "reqInfo": {
    "name": "aims-sg-01",
    "vpcName": "aims-vpc-01",
    "securityRules": [
      {"fromPort": "22", "toPort": "22", "ipProtocol": "tcp", "direction": "inbound", "cidr": "0.0.0.0/0"}
    ]
  }
}

// Response
{
  "name": "aims-sg-01",
  "vpcName": "aims-vpc-01",
  "securityRules": [
    {"fromPort": "22", "toPort": "22", "ipProtocol": "tcp", "direction": "inbound", "cidr": "0.0.0.0/0"}
  ],
  "status": "available"
}
```

**보안규칙 추가 요청/응답:**
```json
// POST /api/v1/network/security-groups/aims-sg-01/rules
// Request
{
  "connectionName": "aws-seoul-conn",
  "reqInfo": {
    "securityRules": [
      {"fromPort": "80", "toPort": "80", "ipProtocol": "tcp", "direction": "inbound", "cidr": "0.0.0.0/0"},
      {"fromPort": "443", "toPort": "443", "ipProtocol": "tcp", "direction": "inbound", "cidr": "0.0.0.0/0"}
    ]
  }
}

// Response
{
  "name": "aims-sg-01",
  "securityRules": [
    {"fromPort": "22", "toPort": "22", "ipProtocol": "tcp", "direction": "inbound", "cidr": "0.0.0.0/0"},
    {"fromPort": "80", "toPort": "80", "ipProtocol": "tcp", "direction": "inbound", "cidr": "0.0.0.0/0"},
    {"fromPort": "443", "toPort": "443", "ipProtocol": "tcp", "direction": "inbound", "cidr": "0.0.0.0/0"}
  ]
}
```

**키페어 생성 요청/응답:**
```json
// POST /api/v1/network/keypairs
// Request
{
  "connectionName": "aws-seoul-conn",
  "reqInfo": {
    "name": "aims-keypair-01"
  }
}

// Response
{
  "name": "aims-keypair-01",
  "fingerprint": "ab:cd:ef:12:34:56:78:90:ab:cd:ef:12:34:56:78:90",
  "privateKey": "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQ...\n-----END RSA PRIVATE KEY-----",
  "publicKey": "ssh-rsa AAAAB3NzaC1yc2EAAAA..."
}
```

**VPC 삭제 응답:**
```json
// DELETE /api/v1/network/vpcs/aims-vpc-01?connectionName=aws-seoul-conn
// Response
{
  "success": true,
  "message": "VPC가 삭제되었습니다."
}
```

**서브넷 삭제 응답:**
```json
// DELETE /api/v1/network/vpcs/aims-vpc-01/subnets/aims-subnet-02?connectionName=aws-seoul-conn
// Response
{
  "success": true,
  "message": "서브넷이 삭제되었습니다."
}
```

**보안그룹 삭제 응답:**
```json
// DELETE /api/v1/network/security-groups/aims-sg-01?connectionName=aws-seoul-conn
// Response
{
  "success": true,
  "message": "보안그룹이 삭제되었습니다."
}
```

**키페어 삭제 응답:**
```json
// DELETE /api/v1/network/keypairs/aims-keypair-01?connectionName=aws-seoul-conn
// Response
{
  "success": true,
  "message": "키페어가 삭제되었습니다."
}
```

---

## 6. 스토리지 (7개)

| Method | URL | 설명 | 파라미터 |
|--------|-----|------|---------|
| GET | `/api/v1/storage/volumes` | 볼륨 목록 | connectionName |
| POST | `/api/v1/storage/volumes` | 볼륨 생성 | Body |
| GET | `/api/v1/storage/volumes/{diskName}` | 볼륨 상세 | diskName, connectionName |
| DELETE | `/api/v1/storage/volumes/{diskName}` | 볼륨 삭제 | diskName, connectionName |
| PUT | `/api/v1/storage/volumes/{diskName}/resize` | 볼륨 크기 변경 | diskName, Body |
| PUT | `/api/v1/storage/volumes/{diskName}/attach` | VM에 연결 | diskName, Body |
| PUT | `/api/v1/storage/volumes/{diskName}/detach` | VM에서 해제 | diskName, Body |

**볼륨 생성 요청/응답:**
```json
// POST /api/v1/storage/volumes
// Request
{
  "connectionName": "aws-seoul-conn",
  "reqInfo": {
    "name": "aims-vol-01",
    "diskType": "gp3",
    "diskSize": "100"
  }
}

// Response
{
  "name": "aims-vol-01",
  "diskType": "gp3",
  "diskSize": "100",
  "status": "available",
  "createdAt": "2026-08-12T11:00:00Z"
}
```

**볼륨 크기 변경 요청/응답:**
```json
// PUT /api/v1/storage/volumes/aims-vol-01/resize
// Request
{
  "connectionName": "aws-seoul-conn",
  "size": "200"
}

// Response
{
  "name": "aims-vol-01",
  "diskSize": "200",
  "status": "available"
}
```

**VM에 연결 요청/응답:**
```json
// PUT /api/v1/storage/volumes/aims-vol-01/attach
// Request
{
  "connectionName": "aws-seoul-conn",
  "vmName": "aims-vm-01"
}

// Response
{
  "name": "aims-vol-01",
  "diskSize": "100",
  "status": "in-use",
  "attachedVm": "aims-vm-01"
}
```

**VM에서 해제 요청/응답:**
```json
// PUT /api/v1/storage/volumes/aims-vol-01/detach
// Request
{
  "connectionName": "aws-seoul-conn",
  "vmName": "aims-vm-01"
}

// Response
{
  "name": "aims-vol-01",
  "diskSize": "100",
  "status": "available",
  "attachedVm": null
}
```

**볼륨 삭제 응답:**
```json
// DELETE /api/v1/storage/volumes/aims-vol-01?connectionName=aws-seoul-conn
// Response
{
  "success": true,
  "message": "볼륨이 삭제되었습니다."
}
```

---

## 7. 이미지/스냅샷 (5개)

| Method | URL | 설명 | 파라미터 |
|--------|-----|------|---------|
| GET | `/api/v1/images` | 이미지 목록 | connectionName |
| GET | `/api/v1/images/{imageName}` | 이미지 상세 | imageName, connectionName |
| GET | `/api/v1/images/snapshots` | 스냅샷 목록 | connectionName |
| POST | `/api/v1/images/snapshots` | 스냅샷 생성 | Body |
| DELETE | `/api/v1/images/snapshots/{imageName}` | 스냅샷 삭제 | imageName, connectionName |

**스냅샷 생성 요청/응답:**
```json
// POST /api/v1/images/snapshots
// Request
{
  "connectionName": "aws-seoul-conn",
  "reqInfo": {
    "name": "aims-snapshot-01",
    "sourceDisk": "aims-vol-01"
  }
}

// Response
{
  "name": "aims-snapshot-01",
  "sourceDisk": "aims-vol-01",
  "status": "available",
  "createdAt": "2026-08-12T11:30:00Z"
}
```

**스냅샷 삭제 응답:**
```json
// DELETE /api/v1/images/snapshots/aims-snapshot-01?connectionName=aws-seoul-conn
// Response
{
  "success": true,
  "message": "스냅샷이 삭제되었습니다."
}
```

---

## 8. 태깅 (3개)

| Method | URL | 설명 | 파라미터 |
|--------|-----|------|---------|
| GET | `/api/v1/tags/{labelType}/{uid}` | 레이블 조회 | labelType, uid |
| PUT | `/api/v1/tags/{labelType}/{uid}` | 레이블 설정 | labelType, uid, Body |
| DELETE | `/api/v1/tags/{labelType}/{uid}/{key}` | 레이블 삭제 | labelType, uid, key |

**레이블 조회 응답:**
```json
// GET /api/v1/tags/VM/aims-vm-01
// Response
{
  "labelType": "VM",
  "uid": "aims-vm-01",
  "labels": {
    "env": "production",
    "team": "클라우드운영팀",
    "project": "서울 MDC 웹서비스",
    "owner": "홍길동"
  }
}
```

**레이블 설정 요청/응답:**
```json
// PUT /api/v1/tags/VM/aims-vm-01
// Request
{
  "labels": {
    "env": "production",
    "team": "클라우드운영팀",
    "project": "서울 MDC 웹서비스",
    "owner": "홍길동"
  }
}

// Response
{
  "labelType": "VM",
  "uid": "aims-vm-01",
  "labels": {
    "env": "production",
    "team": "클라우드운영팀",
    "project": "서울 MDC 웹서비스",
    "owner": "홍길동"
  }
}
```

**레이블 삭제 응답:**
```json
// DELETE /api/v1/tags/VM/aims-vm-01/owner
// Response
{
  "success": true,
  "message": "레이블이 삭제되었습니다."
}
```

---

## 9. 컴퓨팅 (4개)

| Method | URL | 설명 | 파라미터 |
|--------|-----|------|---------|
| GET | `/api/v1/compute/usage` | CPU/MEM/Disk 실시간 사용률 | mdcId |
| GET | `/api/v1/compute/gpu` | GPU 실시간 상태 | mdcId |
| GET | `/api/v1/compute/nodes/{nodeId}/performance` | 노드 성능 상세 | nodeId |
| GET | `/api/v1/compute/specs` | VM 스펙 목록 (CB-Spider) | connectionName |

**실시간 사용률 응답:**
```json
{
  "cpuUsage": 72.5,
  "memoryUsage": 85.3,
  "diskUsage": 45.2,
  "networkRxBps": 1048576.0,
  "networkTxBps": 524288.0
}
```

**GPU 상태 응답:**
```json
{
  "gpuUtilization": 65.0,
  "gpuMemoryUsedMB": 8192.0,
  "gpuMemoryFreeMB": 8192.0,
  "gpuTemperature": 72.0,
  "gpuPowerWatts": 250.0
}
```

---

## 10. 카탈로그 (6개)

| Method | URL | 설명 | 파라미터 |
|--------|-----|------|---------|
| GET | `/api/v1/catalogs` | 카탈로그 항목 목록 | - |
| POST | `/api/v1/catalogs` | 카탈로그 항목 등록 | Body |
| PUT | `/api/v1/catalogs/{catalogId}` | 카탈로그 항목 수정 | catalogId, Body |
| POST | `/api/v1/catalogs/{catalogId}/requests` | 자원 요청 (사용자) | catalogId, Body |
| GET | `/api/v1/catalogs/requests` | 자원 요청 목록 (관리자) | status |
| POST | `/api/v1/catalogs/requests/{requestId}/approve` | 요청 승인/반려 | requestId, approved |

**카탈로그 응답:**
```json
[
  {"catalogId": 1, "name": "웹 서버 (Small)", "cpu": 2, "memoryGb": 4, "diskGb": 50, "os": "Ubuntu 22.04", "monthlyPrice": 50000, "category": "COMPUTE"},
  {"catalogId": 2, "name": "웹 서버 (Medium)", "cpu": 4, "memoryGb": 8, "diskGb": 100, "os": "Ubuntu 22.04", "monthlyPrice": 100000, "category": "COMPUTE"},
  {"catalogId": 3, "name": "GPU 서버", "cpu": 8, "memoryGb": 32, "diskGb": 500, "gpu": "A100", "monthlyPrice": 500000, "category": "GPU"}
]
```

**카탈로그 항목 등록 요청/응답:**
```json
// POST /api/v1/catalogs
// Request
{
  "name": "DB 서버 (Large)",
  "cpu": 8,
  "memoryGb": 64,
  "diskGb": 1000,
  "os": "CentOS 8",
  "monthlyPrice": 300000,
  "category": "COMPUTE",
  "description": "고성능 데이터베이스 서버"
}

// Response
{
  "catalogId": 4,
  "name": "DB 서버 (Large)",
  "cpu": 8,
  "memoryGb": 64,
  "diskGb": 1000,
  "os": "CentOS 8",
  "monthlyPrice": 300000,
  "category": "COMPUTE",
  "description": "고성능 데이터베이스 서버",
  "createdAt": "2026-08-12T10:00:00Z"
}
```

**자원 요청 (사용자) 요청/응답:**
```json
// POST /api/v1/catalogs/1/requests
// Request
{
  "quantity": 2,
  "purpose": "서울 MDC 웹 서비스 확장",
  "desiredDate": "2026-08-20",
  "mdcId": "mdc-01"
}

// Response
{
  "requestId": 101,
  "catalogId": 1,
  "catalogName": "웹 서버 (Small)",
  "userId": 3,
  "userName": "홍길동",
  "quantity": 2,
  "purpose": "서울 MDC 웹 서비스 확장",
  "desiredDate": "2026-08-20",
  "status": "PENDING",
  "createdAt": "2026-08-12T11:00:00Z"
}
```

---

## 11. 미터링 (5개)

| Method | URL | 설명 | 파라미터 |
|--------|-----|------|---------|
| GET | `/api/v1/metering/usage` | 시간별 리소스 사용량 | mdcId, metricName, from, to |
| GET | `/api/v1/metering/daily-summary` | 일간 요약 보고서 | mdcId, from, to |
| GET | `/api/v1/metering/storage-status` | 메트릭 저장소 상태 | - |
| POST | `/api/v1/metering/aggregate` | 수동 집계 트리거 | - |
| POST | `/api/v1/metering/cleanup` | 오래된 데이터 정리 | - |

**시간별 리소스 사용량 응답:**
```json
// GET /api/v1/metering/usage?mdcId=mdc-01&metricName=cpu_usage&from=2026-08-11T00:00:00Z&to=2026-08-11T23:59:59Z
// Response
{
  "mdcId": "mdc-01",
  "metricName": "cpu_usage",
  "unit": "%",
  "data": [
    {"timestamp": "2026-08-11T00:00:00Z", "value": 45.2},
    {"timestamp": "2026-08-11T01:00:00Z", "value": 42.8},
    {"timestamp": "2026-08-11T02:00:00Z", "value": 38.5},
    {"timestamp": "2026-08-11T03:00:00Z", "value": 35.1},
    {"timestamp": "2026-08-11T08:00:00Z", "value": 68.3},
    {"timestamp": "2026-08-11T09:00:00Z", "value": 75.6},
    {"timestamp": "2026-08-11T14:00:00Z", "value": 82.1}
  ]
}
```

**일간 요약 보고서 응답:**
```json
// GET /api/v1/metering/daily-summary?mdcId=mdc-01&from=2026-08-10&to=2026-08-12
// Response
{
  "mdcId": "mdc-01",
  "mdcName": "서울 MDC",
  "summaries": [
    {
      "date": "2026-08-10",
      "avgCpuUsage": 62.3,
      "maxCpuUsage": 88.5,
      "avgMemoryUsage": 71.2,
      "maxMemoryUsage": 92.1,
      "avgDiskUsage": 45.0,
      "totalVmCount": 42,
      "activeVmCount": 38
    },
    {
      "date": "2026-08-11",
      "avgCpuUsage": 65.8,
      "maxCpuUsage": 91.2,
      "avgMemoryUsage": 73.5,
      "maxMemoryUsage": 94.3,
      "avgDiskUsage": 46.2,
      "totalVmCount": 42,
      "activeVmCount": 40
    }
  ]
}
```

**메트릭 저장소 상태 응답:**
```json
// GET /api/v1/metering/storage-status
// Response
{
  "status": "HEALTHY",
  "totalCapacityGb": 500,
  "usedCapacityGb": 128,
  "usagePercent": 25.6,
  "retentionDays": 90,
  "oldestDataDate": "2026-05-12T00:00:00Z",
  "lastAggregationTime": "2026-08-12T00:05:00Z",
  "metricCount": 1245800
}
```

**수동 집계 트리거 응답:**
```json
// POST /api/v1/metering/aggregate
// Response
{
  "success": true,
  "message": "집계가 시작되었습니다.",
  "aggregationId": "agg-20260812-001"
}
```

**오래된 데이터 정리 응답:**
```json
// POST /api/v1/metering/cleanup
// Response
{
  "success": true,
  "message": "데이터 정리가 완료되었습니다.",
  "deletedCount": 53200
}
```

---

## 12. 빌링 (4개)

| Method | URL | 설명 | 파라미터 |
|--------|-----|------|---------|
| GET | `/api/v1/billing/charges` | 청구 금액 조회 | userId, from, to |
| GET | `/api/v1/billing/charges/{chargeId}` | 청구 상세 | chargeId |
| GET | `/api/v1/billing/usage-costs` | 이용금액 현황 | mdcId, period |
| GET | `/api/v1/billing/usage-costs/trend` | 이용금액 추이 | from, to |

**청구 금액 응답:**
```json
{
  "chargeId": 1,
  "userName": "관리자",
  "mdcId": "mdc-01",
  "period": "2026-07",
  "totalAmount": 1250000,
  "details": [
    {"resourceType": "cpu", "usage": 720.5, "unitPrice": 500, "amount": 360250},
    {"resourceType": "memory", "usage": 1024.0, "unitPrice": 300, "amount": 307200},
    {"resourceType": "gpu", "usage": 168.0, "unitPrice": 1500, "amount": 252000}
  ]
}
```

**이용금액 현황 응답:**
```json
// GET /api/v1/billing/usage-costs?mdcId=mdc-01&period=2026-07
// Response
{
  "mdcId": "mdc-01",
  "mdcName": "서울 MDC",
  "period": "2026-07",
  "totalCost": 3850000,
  "costByCategory": [
    {"category": "COMPUTE", "cost": 2100000, "ratio": 54.5},
    {"category": "GPU", "cost": 1200000, "ratio": 31.2},
    {"category": "STORAGE", "cost": 350000, "ratio": 9.1},
    {"category": "NETWORK", "cost": 200000, "ratio": 5.2}
  ],
  "costByUser": [
    {"userId": 1, "userName": "관리자", "cost": 1500000},
    {"userId": 3, "userName": "홍길동", "cost": 1250000},
    {"userId": 4, "userName": "김철수", "cost": 1100000}
  ]
}
```

**이용금액 추이 응답:**
```json
// GET /api/v1/billing/usage-costs/trend?from=2026-04&to=2026-07
// Response
{
  "trend": [
    {"period": "2026-04", "totalCost": 3200000},
    {"period": "2026-05", "totalCost": 3450000},
    {"period": "2026-06", "totalCost": 3680000},
    {"period": "2026-07", "totalCost": 3850000}
  ]
}
```

---

## 13. 최적화 진단 (5개)

| Method | URL | 설명 | 파라미터 |
|--------|-----|------|---------|
| GET | `/api/v1/optimization/cost` | 비용 최적화 진단 | mdcId |
| GET | `/api/v1/optimization/infra-scaling` | 인프라 증설 진단 | mdcId |
| GET | `/api/v1/optimization/vm-rightsizing` | VM 라이트사이징 진단 | mdcId |
| GET | `/api/v1/optimization/settings` | 진단 환경 설정 조회 | - |
| PUT | `/api/v1/optimization/settings` | 진단 환경 설정 변경 | Body |

**비용 최적화 응답:**
```json
{
  "totalSavingPotential": 285000,
  "recommendations": [
    {"type": "VM_RIGHTSIZING", "target": "mdc-01/vm-03", "currentSpec": "8vCPU/32GB", "recommendedSpec": "4vCPU/16GB", "monthlySaving": 125000},
    {"type": "IDLE_RESOURCE", "target": "mdc-02/vm-07", "recommendedSpec": "삭제 권고", "monthlySaving": 80000},
    {"type": "CLOUD_BURSTING", "target": "mdc-01", "recommendedSpec": "AWS t3.medium 3대", "monthlySaving": 80000}
  ]
}
```

**VM 라이트사이징 진단 응답:**
```json
// GET /api/v1/optimization/vm-rightsizing?mdcId=mdc-01
// Response
{
  "mdcId": "mdc-01",
  "mdcName": "서울 MDC",
  "analyzedVmCount": 42,
  "rightsizingTargets": [
    {
      "vmName": "aims-vm-03",
      "currentSpec": {"cpu": 8, "memoryGb": 32},
      "avgCpuUsage": 12.3,
      "avgMemoryUsage": 18.5,
      "recommendedSpec": {"cpu": 4, "memoryGb": 16},
      "monthlySaving": 125000,
      "confidence": "HIGH"
    },
    {
      "vmName": "aims-vm-08",
      "currentSpec": {"cpu": 4, "memoryGb": 16},
      "avgCpuUsage": 8.1,
      "avgMemoryUsage": 22.0,
      "recommendedSpec": {"cpu": 2, "memoryGb": 8},
      "monthlySaving": 50000,
      "confidence": "MEDIUM"
    }
  ]
}
```

**진단 환경 설정 조회 응답:**
```json
// GET /api/v1/optimization/settings
// Response
{
  "cpuThresholdLow": 20.0,
  "cpuThresholdHigh": 80.0,
  "memoryThresholdLow": 20.0,
  "memoryThresholdHigh": 85.0,
  "analysisWindowDays": 7,
  "idleResourceDays": 14,
  "costCurrency": "KRW",
  "enableAutoRecommendation": true,
  "notifyOnNewRecommendation": true
}
```

**진단 환경 설정 변경 요청/응답:**
```json
// PUT /api/v1/optimization/settings
// Request
{
  "cpuThresholdLow": 15.0,
  "cpuThresholdHigh": 85.0,
  "memoryThresholdLow": 15.0,
  "memoryThresholdHigh": 90.0,
  "analysisWindowDays": 14,
  "idleResourceDays": 21,
  "costCurrency": "KRW",
  "enableAutoRecommendation": true,
  "notifyOnNewRecommendation": true
}

// Response
{
  "success": true,
  "message": "설정이 변경되었습니다.",
  "settings": {
    "cpuThresholdLow": 15.0,
    "cpuThresholdHigh": 85.0,
    "memoryThresholdLow": 15.0,
    "memoryThresholdHigh": 90.0,
    "analysisWindowDays": 14,
    "idleResourceDays": 21,
    "costCurrency": "KRW",
    "enableAutoRecommendation": true,
    "notifyOnNewRecommendation": true
  }
}
```

---

## 14. 대시보드 (9개)

| Method | URL | 설명 |
|--------|-----|------|
| GET | `/api/v1/dashboards/infra` | 인프라 통합 대시보드 |
| GET | `/api/v1/dashboards/nodes` | 노드 현황 |
| GET | `/api/v1/dashboards/gpu` | GPU 대시보드 |
| GET | `/api/v1/dashboards/storage` | 스토리지 대시보드 |
| GET | `/api/v1/dashboards/vm-status` | 가상서버 현황 |
| GET | `/api/v1/dashboards/user` | 사용자 대시보드 |
| GET | `/api/v1/dashboards/topology/network` | 네트워크 토폴로지 |
| GET | `/api/v1/dashboards/topology/infra` | 인프라 토폴로지 |
| GET | `/api/v1/dashboards/topology/vm-groups` | 가상서버 그룹 |

**인프라 대시보드 응답:**
```json
{
  "totalMdcCount": 3,
  "totalNodeCount": 24,
  "totalVmCount": 87,
  "totalGpuCount": 8,
  "mdcSummary": [
    {"mdcId": "mdc-01", "name": "서울 MDC", "nodeCount": 10, "vmCount": 42, "avgCpuUsage": 68.5, "status": "NORMAL"},
    {"mdcId": "mdc-02", "name": "대전 MDC", "nodeCount": 8, "vmCount": 30, "avgCpuUsage": 45.2, "status": "NORMAL"},
    {"mdcId": "mdc-03", "name": "부산 MDC", "nodeCount": 6, "vmCount": 15, "avgCpuUsage": 82.3, "status": "WARNING"}
  ],
  "alerts": [
    {"level": "WARNING", "message": "부산 MDC CPU 사용률 82% 초과", "time": "2026-08-11T14:30:00Z"}
  ]
}
```

**노드 현황 응답:**
```json
// GET /api/v1/dashboards/nodes
// Response
{
  "totalNodes": 24,
  "activeNodes": 22,
  "warningNodes": 1,
  "downNodes": 1,
  "nodes": [
    {"nodeId": "node-01", "mdcId": "mdc-01", "mdcName": "서울 MDC", "hostname": "sv-seoul-01", "cpuUsage": 72.5, "memoryUsage": 85.3, "diskUsage": 45.2, "status": "ACTIVE"},
    {"nodeId": "node-02", "mdcId": "mdc-01", "mdcName": "서울 MDC", "hostname": "sv-seoul-02", "cpuUsage": 55.1, "memoryUsage": 62.8, "diskUsage": 38.7, "status": "ACTIVE"},
    {"nodeId": "node-11", "mdcId": "mdc-02", "mdcName": "대전 MDC", "hostname": "sv-daejeon-01", "cpuUsage": 45.2, "memoryUsage": 58.1, "diskUsage": 32.5, "status": "ACTIVE"},
    {"nodeId": "node-19", "mdcId": "mdc-03", "mdcName": "부산 MDC", "hostname": "sv-busan-01", "cpuUsage": 88.7, "memoryUsage": 91.2, "diskUsage": 72.3, "status": "WARNING"}
  ]
}
```

**GPU 대시보드 응답:**
```json
// GET /api/v1/dashboards/gpu
// Response
{
  "totalGpuCount": 8,
  "activeGpuCount": 6,
  "avgGpuUtilization": 65.0,
  "gpuNodes": [
    {
      "nodeId": "node-gpu-01",
      "mdcId": "mdc-01",
      "mdcName": "서울 MDC",
      "hostname": "sv-seoul-gpu-01",
      "gpus": [
        {"gpuIndex": 0, "model": "NVIDIA A100", "utilization": 78.5, "memoryUsedMB": 32768, "memoryTotalMB": 40960, "temperature": 72, "powerWatts": 285},
        {"gpuIndex": 1, "model": "NVIDIA A100", "utilization": 62.3, "memoryUsedMB": 28672, "memoryTotalMB": 40960, "temperature": 68, "powerWatts": 250}
      ]
    },
    {
      "nodeId": "node-gpu-02",
      "mdcId": "mdc-02",
      "mdcName": "대전 MDC",
      "hostname": "sv-daejeon-gpu-01",
      "gpus": [
        {"gpuIndex": 0, "model": "NVIDIA A100", "utilization": 45.2, "memoryUsedMB": 16384, "memoryTotalMB": 40960, "temperature": 58, "powerWatts": 180}
      ]
    }
  ]
}
```

**스토리지 대시보드 응답:**
```json
// GET /api/v1/dashboards/storage
// Response
{
  "totalCapacityTb": 120.0,
  "usedCapacityTb": 78.5,
  "usagePercent": 65.4,
  "storageSummary": [
    {"mdcId": "mdc-01", "mdcName": "서울 MDC", "capacityTb": 50.0, "usedTb": 35.2, "usagePercent": 70.4, "volumeCount": 85, "status": "NORMAL"},
    {"mdcId": "mdc-02", "mdcName": "대전 MDC", "capacityTb": 40.0, "usedTb": 22.8, "usagePercent": 57.0, "volumeCount": 62, "status": "NORMAL"},
    {"mdcId": "mdc-03", "mdcName": "부산 MDC", "capacityTb": 30.0, "usedTb": 20.5, "usagePercent": 68.3, "volumeCount": 38, "status": "NORMAL"}
  ]
}
```

**가상서버 현황 응답:**
```json
// GET /api/v1/dashboards/vm-status
// Response
{
  "totalVmCount": 87,
  "runningCount": 72,
  "stoppedCount": 10,
  "errorCount": 3,
  "suspendedCount": 2,
  "vmsByMdc": [
    {"mdcId": "mdc-01", "mdcName": "서울 MDC", "total": 42, "running": 38, "stopped": 3, "error": 1},
    {"mdcId": "mdc-02", "mdcName": "대전 MDC", "total": 30, "running": 25, "stopped": 4, "error": 1},
    {"mdcId": "mdc-03", "mdcName": "부산 MDC", "total": 15, "running": 9, "stopped": 3, "error": 1}
  ],
  "recentEvents": [
    {"vmName": "aims-vm-12", "event": "STOPPED", "time": "2026-08-12T09:15:00Z", "user": "홍길동"},
    {"vmName": "aims-vm-25", "event": "ERROR", "time": "2026-08-12T08:45:00Z", "message": "디스크 I/O 오류"}
  ]
}
```

**사용자 대시보드 응답:**
```json
// GET /api/v1/dashboards/user
// Response
{
  "userId": 3,
  "userName": "홍길동",
  "myVmCount": 5,
  "myRunningVmCount": 4,
  "myVolumeCount": 8,
  "myMonthlyCost": 650000,
  "myVms": [
    {"vmName": "aims-vm-10", "spec": "4vCPU/8GB", "status": "RUNNING", "mdcName": "서울 MDC", "cpuUsage": 45.2},
    {"vmName": "aims-vm-11", "spec": "2vCPU/4GB", "status": "RUNNING", "mdcName": "서울 MDC", "cpuUsage": 32.1},
    {"vmName": "aims-vm-20", "spec": "8vCPU/32GB", "status": "RUNNING", "mdcName": "대전 MDC", "cpuUsage": 78.5}
  ],
  "pendingRequests": 1,
  "recentAlerts": [
    {"message": "aims-vm-20 CPU 사용률 78% 상승 추세", "time": "2026-08-12T10:00:00Z", "severity": "INFO"}
  ]
}
```

**네트워크 토폴로지 응답:**
```json
{
  "nodes": [
    {"id": "mdc-01", "type": "MDC", "label": "서울 MDC", "x": 100, "y": 200},
    {"id": "aws", "type": "CSP", "label": "AWS", "x": 200, "y": 50}
  ],
  "edges": [
    {"from": "mdc-01", "to": "mdc-02", "bandwidth": "10Gbps", "status": "UP"},
    {"from": "mdc-01", "to": "aws", "bandwidth": "1Gbps", "status": "UP"}
  ]
}
```

---

## 15. 리소스 모니터링 (5개)

| Method | URL | 설명 | 파라미터 |
|--------|-----|------|---------|
| GET | `/api/v1/monitoring/resources/realtime` | 실시간 사용률 | mdcId |
| GET | `/api/v1/monitoring/resources/trend/cpu` | CPU 추이 | mdcId, from, to |
| GET | `/api/v1/monitoring/resources/gpu` | GPU 실시간 | mdcId |
| GET | `/api/v1/monitoring/resources/trend/gpu` | GPU 추이 | mdcId, from, to |
| GET | `/api/v1/monitoring/resources/nodes/{nodeId}` | 노드 성능 상세 | nodeId |

**노드 성능 응답:**
```json
{
  "load1": 2.5, "load5": 2.1, "load15": 1.8,
  "procsRunning": 42,
  "cpuUsage": 72.5, "memoryUsage": 85.3, "diskUsage": 45.2
}
```

**CPU 추이 응답:**
```json
// GET /api/v1/monitoring/resources/trend/cpu?mdcId=mdc-01&from=2026-08-11T00:00:00Z&to=2026-08-11T23:59:59Z
// Response
{
  "mdcId": "mdc-01",
  "mdcName": "서울 MDC",
  "metric": "cpu_usage",
  "unit": "%",
  "dataPoints": [
    {"timestamp": "2026-08-11T00:00:00Z", "avg": 42.3, "max": 78.5, "min": 18.2},
    {"timestamp": "2026-08-11T01:00:00Z", "avg": 38.7, "max": 65.2, "min": 15.8},
    {"timestamp": "2026-08-11T02:00:00Z", "avg": 35.1, "max": 58.3, "min": 12.5},
    {"timestamp": "2026-08-11T08:00:00Z", "avg": 65.8, "max": 88.2, "min": 42.1},
    {"timestamp": "2026-08-11T09:00:00Z", "avg": 72.3, "max": 91.5, "min": 55.3},
    {"timestamp": "2026-08-11T14:00:00Z", "avg": 68.5, "max": 85.7, "min": 48.2}
  ]
}
```

**GPU 추이 응답:**
```json
// GET /api/v1/monitoring/resources/trend/gpu?mdcId=mdc-01&from=2026-08-11T00:00:00Z&to=2026-08-11T23:59:59Z
// Response
{
  "mdcId": "mdc-01",
  "mdcName": "서울 MDC",
  "metric": "gpu_utilization",
  "unit": "%",
  "dataPoints": [
    {"timestamp": "2026-08-11T00:00:00Z", "avg": 55.2, "max": 82.1, "min": 28.5},
    {"timestamp": "2026-08-11T01:00:00Z", "avg": 52.8, "max": 78.3, "min": 25.2},
    {"timestamp": "2026-08-11T08:00:00Z", "avg": 72.5, "max": 95.2, "min": 48.3},
    {"timestamp": "2026-08-11T09:00:00Z", "avg": 78.1, "max": 98.5, "min": 55.8},
    {"timestamp": "2026-08-11T14:00:00Z", "avg": 65.3, "max": 88.7, "min": 42.1}
  ]
}
```

---

## 16. 물리 자산 모니터링 (3개)

| Method | URL | 설명 | 파라미터 |
|--------|-----|------|---------|
| GET | `/api/v1/monitoring/physical/{serverId}` | 물리 서버 상태 | serverId |
| GET | `/api/v1/monitoring/physical/power/trend` | 전력 추이 | mdcId, from, to |
| GET | `/api/v1/monitoring/physical/temperature/trend` | 온도 추이 | mdcId, from, to |

**물리 서버 응답:**
```json
{
  "serverId": "192.168.1.10",
  "powerWatts": 350.0,
  "temperatures": [
    {"name": "CPU Temp", "value": 65.0, "unit": "°C"},
    {"name": "System Temp", "value": 42.0, "unit": "°C"}
  ],
  "fanSpeeds": [
    {"name": "Fan 1", "value": 8500, "unit": "RPM"}
  ]
}
```

**전력 추이 응답:**
```json
// GET /api/v1/monitoring/physical/power/trend?mdcId=mdc-01&from=2026-08-11T00:00:00Z&to=2026-08-11T23:59:59Z
// Response
{
  "mdcId": "mdc-01",
  "mdcName": "서울 MDC",
  "metric": "power_consumption",
  "unit": "kW",
  "dataPoints": [
    {"timestamp": "2026-08-11T00:00:00Z", "totalPowerKw": 42.5, "serverCount": 10},
    {"timestamp": "2026-08-11T04:00:00Z", "totalPowerKw": 38.2, "serverCount": 10},
    {"timestamp": "2026-08-11T08:00:00Z", "totalPowerKw": 52.8, "serverCount": 10},
    {"timestamp": "2026-08-11T12:00:00Z", "totalPowerKw": 58.3, "serverCount": 10},
    {"timestamp": "2026-08-11T16:00:00Z", "totalPowerKw": 55.1, "serverCount": 10},
    {"timestamp": "2026-08-11T20:00:00Z", "totalPowerKw": 45.6, "serverCount": 10}
  ]
}
```

**온도 추이 응답:**
```json
// GET /api/v1/monitoring/physical/temperature/trend?mdcId=mdc-01&from=2026-08-11T00:00:00Z&to=2026-08-11T23:59:59Z
// Response
{
  "mdcId": "mdc-01",
  "mdcName": "서울 MDC",
  "metric": "temperature",
  "unit": "°C",
  "dataPoints": [
    {"timestamp": "2026-08-11T00:00:00Z", "avgCpuTemp": 58.2, "maxCpuTemp": 68.5, "avgSystemTemp": 38.1},
    {"timestamp": "2026-08-11T04:00:00Z", "avgCpuTemp": 55.8, "maxCpuTemp": 62.3, "avgSystemTemp": 36.5},
    {"timestamp": "2026-08-11T08:00:00Z", "avgCpuTemp": 65.3, "maxCpuTemp": 75.8, "avgSystemTemp": 42.1},
    {"timestamp": "2026-08-11T12:00:00Z", "avgCpuTemp": 72.1, "maxCpuTemp": 82.5, "avgSystemTemp": 45.8},
    {"timestamp": "2026-08-11T16:00:00Z", "avgCpuTemp": 68.5, "maxCpuTemp": 78.2, "avgSystemTemp": 43.2},
    {"timestamp": "2026-08-11T20:00:00Z", "avgCpuTemp": 60.8, "maxCpuTemp": 70.1, "avgSystemTemp": 39.5}
  ]
}
```

---

## 17. 이벤트 (2개)

| Method | URL | 설명 | 파라미터 |
|--------|-----|------|---------|
| GET | `/api/v1/monitoring/events` | 이벤트 목록 | mdcId, severity, from, to, page, size |
| GET | `/api/v1/monitoring/events/{eventId}` | 이벤트 상세 | eventId |

**이벤트 응답:**
```json
[
  {"eventId": 1, "mdcId": "mdc-01", "severity": "WARNING", "source": "node-03", "message": "CPU 사용률 88% 초과", "time": "2026-08-11T14:30:00Z", "status": "OPEN"},
  {"eventId": 2, "mdcId": "mdc-03", "severity": "CRITICAL", "source": "node-06", "message": "디스크 사용률 95% 초과", "time": "2026-08-11T14:15:00Z", "status": "OPEN"}
]
```

---

## 18. 알람 (6개)

| Method | URL | 설명 | 파라미터 |
|--------|-----|------|---------|
| GET | `/api/v1/monitoring/alarms/rules` | 알람 규칙 목록 | - |
| POST | `/api/v1/monitoring/alarms/rules` | 알람 규칙 생성 | Body |
| PUT | `/api/v1/monitoring/alarms/rules/{ruleId}` | 알람 규칙 수정 | ruleId, Body |
| DELETE | `/api/v1/monitoring/alarms/rules/{ruleId}` | 알람 규칙 삭제 | ruleId |
| GET | `/api/v1/monitoring/alarms/history` | 알람 이력 | mdcId, status, page, size |
| PATCH | `/api/v1/monitoring/alarms/history/{alarmId}/status` | 알람 상태 변경 | alarmId, status |

**알람 규칙 요청:**
```json
{
  "name": "CPU 사용률 90% 초과",
  "metricName": "cpu_usage",
  "condition": "gt",
  "threshold": 90.0,
  "severity": "WARNING",
  "notifyChannel": "email"
}
```

**알람 규칙 목록 응답:**
```json
// GET /api/v1/monitoring/alarms/rules
// Response
[
  {
    "ruleId": 1,
    "name": "CPU 사용률 90% 초과",
    "metricName": "cpu_usage",
    "condition": "gt",
    "threshold": 90.0,
    "severity": "WARNING",
    "notifyChannel": "email",
    "enabled": true,
    "createdAt": "2026-08-01T00:00:00Z"
  },
  {
    "ruleId": 2,
    "name": "메모리 사용률 95% 초과",
    "metricName": "memory_usage",
    "condition": "gt",
    "threshold": 95.0,
    "severity": "CRITICAL",
    "notifyChannel": "email",
    "enabled": true,
    "createdAt": "2026-08-01T00:00:00Z"
  },
  {
    "ruleId": 3,
    "name": "디스크 사용률 90% 초과",
    "metricName": "disk_usage",
    "condition": "gt",
    "threshold": 90.0,
    "severity": "WARNING",
    "notifyChannel": "email",
    "enabled": true,
    "createdAt": "2026-08-02T09:00:00Z"
  }
]
```

**알람 이력 응답:**
```json
// GET /api/v1/monitoring/alarms/history?mdcId=mdc-01&status=OPEN&page=0&size=10
// Response
{
  "content": [
    {
      "alarmId": 1001,
      "ruleId": 1,
      "ruleName": "CPU 사용률 90% 초과",
      "mdcId": "mdc-01",
      "source": "node-03",
      "severity": "WARNING",
      "message": "CPU 사용률 91.2%로 임계치(90%) 초과",
      "currentValue": 91.2,
      "threshold": 90.0,
      "status": "OPEN",
      "triggeredAt": "2026-08-11T14:30:00Z",
      "acknowledgedAt": null
    },
    {
      "alarmId": 1002,
      "ruleId": 2,
      "ruleName": "메모리 사용률 95% 초과",
      "mdcId": "mdc-01",
      "source": "node-05",
      "severity": "CRITICAL",
      "message": "메모리 사용률 96.8%로 임계치(95%) 초과",
      "currentValue": 96.8,
      "threshold": 95.0,
      "status": "OPEN",
      "triggeredAt": "2026-08-11T15:10:00Z",
      "acknowledgedAt": null
    }
  ],
  "totalElements": 2,
  "totalPages": 1,
  "page": 0,
  "size": 10
}
```

**알람 규칙 삭제 응답:**
```json
// DELETE /api/v1/monitoring/alarms/rules/3
// Response
{
  "success": true,
  "message": "알람 규칙이 삭제되었습니다."
}
```

---

## 19. 로그 (2개)

| Method | URL | 설명 | 파라미터 |
|--------|-----|------|---------|
| GET | `/api/v1/monitoring/logs/search` | 로그 탐색 | mdcId, keyword, level, from, to, page, size |
| GET | `/api/v1/monitoring/logs/system` | 시스템 로그 | mdcId, page, size |

**로그 탐색 응답:**
```json
// GET /api/v1/monitoring/logs/search?mdcId=mdc-01&keyword=error&level=ERROR&from=2026-08-11T00:00:00Z&to=2026-08-11T23:59:59Z&page=0&size=10
// Response
{
  "content": [
    {
      "logId": "log-20260811-001",
      "timestamp": "2026-08-11T14:32:15Z",
      "level": "ERROR",
      "source": "node-03",
      "service": "kubelet",
      "message": "Failed to pull image \"nginx:latest\": rpc error: code = Unknown desc = Error response from daemon",
      "mdcId": "mdc-01"
    },
    {
      "logId": "log-20260811-002",
      "timestamp": "2026-08-11T15:05:22Z",
      "level": "ERROR",
      "source": "node-05",
      "service": "docker",
      "message": "OOM killed process 12345 (java) total-vm:4096000kB",
      "mdcId": "mdc-01"
    }
  ],
  "totalElements": 2,
  "totalPages": 1,
  "page": 0,
  "size": 10
}
```

**시스템 로그 응답:**
```json
// GET /api/v1/monitoring/logs/system?mdcId=mdc-01&page=0&size=10
// Response
{
  "content": [
    {
      "logId": "sys-20260811-001",
      "timestamp": "2026-08-11T00:00:05Z",
      "level": "INFO",
      "source": "aims-backend",
      "message": "일간 미터링 집계 작업 시작",
      "mdcId": "mdc-01"
    },
    {
      "logId": "sys-20260811-002",
      "timestamp": "2026-08-11T00:02:18Z",
      "level": "INFO",
      "source": "aims-backend",
      "message": "일간 미터링 집계 작업 완료 (처리: 24,500건)",
      "mdcId": "mdc-01"
    },
    {
      "logId": "sys-20260811-003",
      "timestamp": "2026-08-11T09:15:30Z",
      "level": "WARN",
      "source": "aims-backend",
      "message": "Prometheus 연결 지연 발생 (응답시간: 5,200ms)",
      "mdcId": "mdc-01"
    }
  ],
  "totalElements": 3,
  "totalPages": 1,
  "page": 0,
  "size": 10
}
```

---

## 20. 모니터링 에이전트 (9개)

| Method | URL | 설명 | 파라미터 |
|--------|-----|------|---------|
| GET | `/api/v1/monitoring/agents` | 수집 대상 목록 | mdcId |
| POST | `/api/v1/monitoring/agents` | 수집 대상 등록 | Body |
| GET | `/api/v1/monitoring/agents/{agentId}` | 수집 대상 상세 | agentId |
| DELETE | `/api/v1/monitoring/agents/{agentId}` | 수집 대상 삭제 | agentId |
| POST | `/api/v1/monitoring/agents/refresh` | 에이전트 상태 수동 갱신 | - |
| GET | `/api/v1/monitoring/agents/prometheus-targets` | Prometheus 타겟 JSON | - |
| POST | `/api/v1/monitoring/agents/prometheus-targets/export` | 타겟 파일 수동 갱신 | - |
| GET | `/api/v1/monitoring/agents/metrics-catalog` | 전체 메트릭 카탈로그 | - |
| GET | `/api/v1/monitoring/agents/metrics-catalog/{exporterType}` | Exporter별 카탈로그 | exporterType |

**수집 대상 등록 요청:**
```json
{
  "mdcId": "mdc-01",
  "hostname": "node-1",
  "address": "192.168.1.10",
  "port": 9100,
  "exporterType": "NODE_EXPORTER"
}
```

**수집 대상 목록 응답:**
```json
// GET /api/v1/monitoring/agents?mdcId=mdc-01
// Response
[
  {
    "agentId": 1,
    "mdcId": "mdc-01",
    "hostname": "node-1",
    "address": "192.168.1.10",
    "port": 9100,
    "exporterType": "NODE_EXPORTER",
    "status": "UP",
    "lastSeen": "2026-08-12T10:30:00Z"
  },
  {
    "agentId": 2,
    "mdcId": "mdc-01",
    "hostname": "node-2",
    "address": "192.168.1.11",
    "port": 9100,
    "exporterType": "NODE_EXPORTER",
    "status": "UP",
    "lastSeen": "2026-08-12T10:30:00Z"
  },
  {
    "agentId": 3,
    "mdcId": "mdc-01",
    "hostname": "gpu-node-1",
    "address": "192.168.1.20",
    "port": 9400,
    "exporterType": "NVIDIA_GPU_EXPORTER",
    "status": "UP",
    "lastSeen": "2026-08-12T10:29:55Z"
  }
]
```

**수집 대상 삭제 응답:**
```json
// DELETE /api/v1/monitoring/agents/3
// Response
{
  "success": true,
  "message": "수집 대상이 삭제되었습니다."
}
```

**Prometheus 타겟 JSON 응답:**
```json
// GET /api/v1/monitoring/agents/prometheus-targets
// Response
[
  {
    "targets": ["192.168.1.10:9100", "192.168.1.11:9100"],
    "labels": {
      "__meta_mdcId": "mdc-01",
      "exporter_type": "NODE_EXPORTER",
      "mdcName": "서울 MDC"
    }
  },
  {
    "targets": ["192.168.1.20:9400"],
    "labels": {
      "__meta_mdcId": "mdc-01",
      "exporter_type": "NVIDIA_GPU_EXPORTER",
      "mdcName": "서울 MDC"
    }
  },
  {
    "targets": ["192.168.2.10:9100", "192.168.2.11:9100"],
    "labels": {
      "__meta_mdcId": "mdc-02",
      "exporter_type": "NODE_EXPORTER",
      "mdcName": "대전 MDC"
    }
  }
]
```

**전체 메트릭 카탈로그 응답:**
```json
// GET /api/v1/monitoring/agents/metrics-catalog
// Response
{
  "totalMetrics": 85,
  "exporterTypes": ["NODE_EXPORTER", "NVIDIA_GPU_EXPORTER", "IPMI_EXPORTER"],
  "catalog": [
    {
      "exporterType": "NODE_EXPORTER",
      "metricCount": 52,
      "metrics": [
        {"name": "node_cpu_seconds_total", "type": "counter", "description": "CPU 사용 시간 (초)"},
        {"name": "node_memory_MemTotal_bytes", "type": "gauge", "description": "전체 메모리 (바이트)"},
        {"name": "node_memory_MemAvailable_bytes", "type": "gauge", "description": "사용 가능 메모리 (바이트)"},
        {"name": "node_disk_read_bytes_total", "type": "counter", "description": "디스크 읽기 바이트"}
      ]
    },
    {
      "exporterType": "NVIDIA_GPU_EXPORTER",
      "metricCount": 18,
      "metrics": [
        {"name": "nvidia_gpu_utilization", "type": "gauge", "description": "GPU 사용률 (%)"},
        {"name": "nvidia_gpu_memory_used_bytes", "type": "gauge", "description": "GPU 메모리 사용량 (바이트)"},
        {"name": "nvidia_gpu_temperature", "type": "gauge", "description": "GPU 온도 (°C)"}
      ]
    },
    {
      "exporterType": "IPMI_EXPORTER",
      "metricCount": 15,
      "metrics": [
        {"name": "ipmi_power_watts", "type": "gauge", "description": "전력 소비 (W)"},
        {"name": "ipmi_temperature_celsius", "type": "gauge", "description": "온도 (°C)"},
        {"name": "ipmi_fan_speed_rpm", "type": "gauge", "description": "팬 속도 (RPM)"}
      ]
    }
  ]
}
```

**에이전트 상태 수동 갱신 응답:**
```json
// POST /api/v1/monitoring/agents/refresh
// Response
{
  "success": true,
  "message": "에이전트 상태 갱신이 완료되었습니다.",
  "totalAgents": 15,
  "upCount": 13,
  "downCount": 2
}
```

**타겟 파일 수동 갱신 응답:**
```json
// POST /api/v1/monitoring/agents/prometheus-targets/export
// Response
{
  "success": true,
  "message": "Prometheus 타겟 파일이 갱신되었습니다.",
  "exportedTargetCount": 15
}
```

---

## 21. 자산 관리 (5개)

| Method | URL | 설명 | 파라미터 |
|--------|-----|------|---------|
| GET | `/api/v1/assets` | 자산 목록 | mdcId, type |
| POST | `/api/v1/assets` | 자산 등록 | Body |
| GET | `/api/v1/assets/{assetId}` | 자산 상세 | assetId |
| PUT | `/api/v1/assets/{assetId}` | 자산 수정 | assetId, Body |
| DELETE | `/api/v1/assets/{assetId}` | 자산 삭제 | assetId |

**자산 응답:**
```json
[
  {"assetId": 1, "mdcId": "mdc-01", "type": "SERVER", "name": "sv-seoul-01", "manufacturer": "Dell", "model": "PowerEdge R750", "serialNumber": "SV01-2024-001", "location": "Rack-A-01-U1", "status": "ACTIVE"},
  {"assetId": 2, "mdcId": "mdc-01", "type": "GPU_SERVER", "name": "sv-seoul-gpu-01", "manufacturer": "Dell", "model": "PowerEdge XE9680", "location": "Rack-A-02-U1", "status": "ACTIVE"}
]
```

**자산 등록 요청/응답:**
```json
// POST /api/v1/assets
// Request
{
  "mdcId": "mdc-01",
  "type": "SERVER",
  "name": "sv-seoul-03",
  "manufacturer": "Dell",
  "model": "PowerEdge R750",
  "serialNumber": "SV01-2024-003",
  "location": "Rack-A-01-U5",
  "cpu": "Intel Xeon Gold 6342 x2",
  "memoryGb": 256,
  "diskTb": 4.8,
  "purchaseDate": "2026-07-01",
  "warrantyEndDate": "2029-06-30"
}

// Response
{
  "assetId": 3,
  "mdcId": "mdc-01",
  "type": "SERVER",
  "name": "sv-seoul-03",
  "manufacturer": "Dell",
  "model": "PowerEdge R750",
  "serialNumber": "SV01-2024-003",
  "location": "Rack-A-01-U5",
  "cpu": "Intel Xeon Gold 6342 x2",
  "memoryGb": 256,
  "diskTb": 4.8,
  "purchaseDate": "2026-07-01",
  "warrantyEndDate": "2029-06-30",
  "status": "ACTIVE",
  "createdAt": "2026-08-12T10:00:00Z"
}
```

**자산 수정 요청/응답:**
```json
// PUT /api/v1/assets/3
// Request
{
  "location": "Rack-A-01-U7",
  "memoryGb": 512,
  "status": "ACTIVE"
}

// Response
{
  "assetId": 3,
  "mdcId": "mdc-01",
  "type": "SERVER",
  "name": "sv-seoul-03",
  "manufacturer": "Dell",
  "model": "PowerEdge R750",
  "serialNumber": "SV01-2024-003",
  "location": "Rack-A-01-U7",
  "cpu": "Intel Xeon Gold 6342 x2",
  "memoryGb": 512,
  "diskTb": 4.8,
  "purchaseDate": "2026-07-01",
  "warrantyEndDate": "2029-06-30",
  "status": "ACTIVE",
  "createdAt": "2026-08-12T10:00:00Z"
}
```

**자산 삭제 응답:**
```json
// DELETE /api/v1/assets/3
// Response
{
  "success": true,
  "message": "자산이 삭제되었습니다."
}
```

---

## 22. 랙실장도 (4개)

| Method | URL | 설명 | 파라미터 |
|--------|-----|------|---------|
| GET | `/api/v1/racks` | 랙 목록 | mdcId |
| POST | `/api/v1/racks` | 랙 등록 | Body |
| GET | `/api/v1/racks/{rackId}` | 랙 상세 (실장 장비 포함) | rackId |
| POST | `/api/v1/racks/{rackId}/units` | 장비 배치 | rackId, Body |

**랙실장도 응답:**
```json
{
  "rackId": 1, "mdcId": "mdc-01", "name": "Rack-A-01",
  "totalUnits": 42, "usedUnits": 28,
  "powerCapacityKw": 10.0, "currentPowerKw": 6.8,
  "units": [
    {"position": "U1-U2", "assetName": "sv-seoul-01", "type": "SERVER"},
    {"position": "U40", "assetName": "sw-seoul-san-01", "type": "SWITCH"}
  ]
}
```

**랙 등록 요청/응답:**
```json
// POST /api/v1/racks
// Request
{
  "mdcId": "mdc-01",
  "name": "Rack-A-03",
  "totalUnits": 42,
  "powerCapacityKw": 10.0,
  "location": "서울 MDC A열 3번",
  "description": "GPU 서버 전용 랙"
}

// Response
{
  "rackId": 3,
  "mdcId": "mdc-01",
  "name": "Rack-A-03",
  "totalUnits": 42,
  "usedUnits": 0,
  "powerCapacityKw": 10.0,
  "currentPowerKw": 0.0,
  "location": "서울 MDC A열 3번",
  "description": "GPU 서버 전용 랙",
  "units": [],
  "createdAt": "2026-08-12T10:00:00Z"
}
```

**장비 배치 요청/응답:**
```json
// POST /api/v1/racks/3/units
// Request
{
  "assetId": 2,
  "startUnit": 1,
  "unitSize": 4,
  "side": "FRONT"
}

// Response
{
  "rackId": 3,
  "assetId": 2,
  "assetName": "sv-seoul-gpu-01",
  "position": "U1-U4",
  "side": "FRONT",
  "assignedAt": "2026-08-12T10:30:00Z"
}
```

---

## 23. IP 관리 (4개)

| Method | URL | 설명 | 파라미터 |
|--------|-----|------|---------|
| GET | `/api/v1/ips` | IP 할당 현황 | mdcId |
| POST | `/api/v1/ips` | IP 할당 | Body |
| DELETE | `/api/v1/ips/{ipId}` | IP 회수 | ipId |
| GET | `/api/v1/ips/subnets` | 서브넷 정보 | mdcId |

**IP 할당 현황 응답:**
```json
// GET /api/v1/ips?mdcId=mdc-01
// Response
{
  "mdcId": "mdc-01",
  "mdcName": "서울 MDC",
  "totalIps": 256,
  "allocatedIps": 48,
  "availableIps": 208,
  "allocations": [
    {"ipId": 1, "address": "192.168.1.10", "subnet": "192.168.1.0/24", "assignedTo": "sv-seoul-01", "type": "SERVER", "status": "ALLOCATED", "allocatedAt": "2026-07-01T00:00:00Z"},
    {"ipId": 2, "address": "192.168.1.11", "subnet": "192.168.1.0/24", "assignedTo": "sv-seoul-02", "type": "SERVER", "status": "ALLOCATED", "allocatedAt": "2026-07-01T00:00:00Z"},
    {"ipId": 3, "address": "192.168.1.20", "subnet": "192.168.1.0/24", "assignedTo": "sv-seoul-gpu-01", "type": "GPU_SERVER", "status": "ALLOCATED", "allocatedAt": "2026-07-15T00:00:00Z"},
    {"ipId": 10, "address": "192.168.2.10", "subnet": "192.168.2.0/24", "assignedTo": "aims-vm-01", "type": "VM", "status": "ALLOCATED", "allocatedAt": "2026-08-01T10:00:00Z"}
  ]
}
```

**IP 할당 요청/응답:**
```json
// POST /api/v1/ips
// Request
{
  "mdcId": "mdc-01",
  "subnet": "192.168.2.0/24",
  "address": "192.168.2.50",
  "assignedTo": "aims-vm-10",
  "type": "VM",
  "description": "웹 서비스 VM"
}

// Response
{
  "ipId": 11,
  "mdcId": "mdc-01",
  "address": "192.168.2.50",
  "subnet": "192.168.2.0/24",
  "assignedTo": "aims-vm-10",
  "type": "VM",
  "description": "웹 서비스 VM",
  "status": "ALLOCATED",
  "allocatedAt": "2026-08-12T11:00:00Z"
}
```

**IP 회수 응답:**
```json
// DELETE /api/v1/ips/11
// Response
{
  "success": true,
  "message": "IP가 회수되었습니다."
}
```

**서브넷 정보 응답:**
```json
// GET /api/v1/ips/subnets?mdcId=mdc-01
// Response
[
  {"subnet": "192.168.1.0/24", "gateway": "192.168.1.1", "totalIps": 254, "allocatedIps": 12, "availableIps": 242, "purpose": "물리 서버 관리망"},
  {"subnet": "192.168.2.0/24", "gateway": "192.168.2.1", "totalIps": 254, "allocatedIps": 36, "availableIps": 218, "purpose": "VM 서비스망"},
  {"subnet": "10.0.1.0/24", "gateway": "10.0.1.1", "totalIps": 254, "allocatedIps": 0, "availableIps": 254, "purpose": "스토리지 전용망"}
]
```

---

## 24. 시스템 (1개)

| Method | URL | 설명 | 인증 |
|--------|-----|------|------|
| GET | `/api/ping` | 헬스체크 | 불필요 |

```json
{"message": "pong", "profile": "local", "startedAt": "2026-08-12T00:00:00Z"}
```
