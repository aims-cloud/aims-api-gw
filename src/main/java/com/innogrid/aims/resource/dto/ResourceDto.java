package com.innogrid.aims.resource.dto;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

public class ResourceDto {

    // ── VM ───────────────────────────────────────────────────────

    @Schema(description = "VM 생성 요청 (CB-Spider)")
    public record CreateVmRequest(
            @Schema(description = "클라우드 연결 설정명", example = "aws-seoul-conn", requiredMode = Schema.RequiredMode.REQUIRED)
            String connectionName,
            @Schema(description = "VM 생성 정보", requiredMode = Schema.RequiredMode.REQUIRED)
            VmReqInfo reqInfo
    ) {}

    @Schema(description = "VM 생성 상세 정보")
    public record VmReqInfo(
            @Schema(description = "VM 이름", example = "aims-vm-01", requiredMode = Schema.RequiredMode.REQUIRED)
            String name,
            @Schema(description = "OS 이미지 이름", example = "ubuntu-22.04", requiredMode = Schema.RequiredMode.REQUIRED)
            String imageName,
            @Schema(description = "VPC 이름", example = "aims-vpc-01", requiredMode = Schema.RequiredMode.REQUIRED)
            String vpcName,
            @Schema(description = "서브넷 이름", example = "aims-subnet-01", requiredMode = Schema.RequiredMode.REQUIRED)
            String subnetName,
            @Schema(description = "보안그룹 이름 목록", example = "[\"aims-sg-01\"]", requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> securityGroupNames,
            @Schema(description = "VM 스펙", example = "t3.medium", requiredMode = Schema.RequiredMode.REQUIRED)
            String vmSpecName,
            @Schema(description = "키페어 이름", example = "aims-keypair-01", requiredMode = Schema.RequiredMode.REQUIRED)
            String keyPairName
    ) {}

    @Schema(description = "VM 제어 요청")
    public record VmControlRequest(
            @Schema(description = "제어 액션", example = "suspend", allowableValues = {"suspend", "resume", "reboot"}, requiredMode = Schema.RequiredMode.REQUIRED)
            String action
    ) {}

    @Schema(description = "멀티클라우드 인프라 생성 요청 (CB-Tumblebug)")
    public record CreateInfraRequest(
            @Schema(description = "네임스페이스 ID", example = "ns01", requiredMode = Schema.RequiredMode.REQUIRED)
            String nsId,
            @Schema(description = "인프라 이름", example = "aims-infra-01", requiredMode = Schema.RequiredMode.REQUIRED)
            String name,
            @Schema(description = "설명", example = "AIMS 테스트 인프라")
            String description,
            @Schema(description = "VM 스펙 목록")
            List<Map<String, Object>> vmSpec
    ) {}

    // ── 디스크 ──────────────────────────────────────────────────

    @Schema(description = "디스크 생성 요청")
    public record CreateDiskRequest(
            @Schema(description = "클라우드 연결 설정명", example = "aws-seoul-conn", requiredMode = Schema.RequiredMode.REQUIRED)
            String connectionName,
            @Schema(description = "디스크 생성 정보", requiredMode = Schema.RequiredMode.REQUIRED)
            DiskReqInfo reqInfo
    ) {}

    @Schema(description = "디스크 생성 상세 정보")
    public record DiskReqInfo(
            @Schema(description = "디스크 이름", example = "aims-disk-01", requiredMode = Schema.RequiredMode.REQUIRED)
            String name,
            @Schema(description = "디스크 유형", example = "gp3", requiredMode = Schema.RequiredMode.REQUIRED)
            String diskType,
            @Schema(description = "디스크 크기 (GB)", example = "100", requiredMode = Schema.RequiredMode.REQUIRED)
            String diskSize
    ) {}

    @Schema(description = "디스크 크기 변경 요청")
    public record ResizeDiskRequest(
            @Schema(description = "클라우드 연결 설정명", example = "aws-seoul-conn", requiredMode = Schema.RequiredMode.REQUIRED)
            String connectionName,
            @Schema(description = "변경할 크기 (GB)", example = "200", requiredMode = Schema.RequiredMode.REQUIRED)
            String size
    ) {}

    @Schema(description = "디스크 VM 연결/해제 요청")
    public record AttachDiskRequest(
            @Schema(description = "클라우드 연결 설정명", example = "aws-seoul-conn", requiredMode = Schema.RequiredMode.REQUIRED)
            String connectionName,
            @Schema(description = "연결할 VM 이름", example = "aims-vm-01", requiredMode = Schema.RequiredMode.REQUIRED)
            String vmName
    ) {}

    // ── 네트워크 ────────────────────────────────────────────────

    @Schema(description = "VPC 생성 요청")
    public record CreateVpcRequest(
            @Schema(description = "클라우드 연결 설정명", example = "aws-seoul-conn", requiredMode = Schema.RequiredMode.REQUIRED)
            String connectionName,
            @Schema(description = "VPC 생성 정보", requiredMode = Schema.RequiredMode.REQUIRED)
            VpcReqInfo reqInfo
    ) {}

    @Schema(description = "VPC 생성 상세 정보")
    public record VpcReqInfo(
            @Schema(description = "VPC 이름", example = "aims-vpc-01", requiredMode = Schema.RequiredMode.REQUIRED)
            String name,
            @Schema(description = "CIDR 대역", example = "10.0.0.0/16", requiredMode = Schema.RequiredMode.REQUIRED)
            String ipv4Cidr,
            @Schema(description = "서브넷 목록")
            List<SubnetInfo> subnetInfoList
    ) {}

    @Schema(description = "서브넷 정보")
    public record SubnetInfo(
            @Schema(description = "서브넷 이름", example = "aims-subnet-01", requiredMode = Schema.RequiredMode.REQUIRED)
            String name,
            @Schema(description = "CIDR 대역", example = "10.0.1.0/24", requiredMode = Schema.RequiredMode.REQUIRED)
            String ipv4Cidr
    ) {}

    @Schema(description = "서브넷 추가 요청")
    public record AddSubnetRequest(
            @Schema(description = "클라우드 연결 설정명", example = "aws-seoul-conn", requiredMode = Schema.RequiredMode.REQUIRED)
            String connectionName,
            @Schema(description = "서브넷 정보", requiredMode = Schema.RequiredMode.REQUIRED)
            SubnetInfo reqInfo
    ) {}

    @Schema(description = "보안그룹 생성 요청")
    public record CreateSecurityGroupRequest(
            @Schema(description = "클라우드 연결 설정명", example = "aws-seoul-conn", requiredMode = Schema.RequiredMode.REQUIRED)
            String connectionName,
            @Schema(description = "보안그룹 생성 정보", requiredMode = Schema.RequiredMode.REQUIRED)
            SgReqInfo reqInfo
    ) {}

    @Schema(description = "보안그룹 생성 상세 정보")
    public record SgReqInfo(
            @Schema(description = "보안그룹 이름", example = "aims-sg-01", requiredMode = Schema.RequiredMode.REQUIRED)
            String name,
            @Schema(description = "VPC 이름", example = "aims-vpc-01", requiredMode = Schema.RequiredMode.REQUIRED)
            String vpcName,
            @Schema(description = "보안 규칙 목록")
            List<SecurityRule> securityRules
    ) {}

    @Schema(description = "보안그룹 규칙")
    public record SecurityRule(
            @Schema(description = "시작 포트", example = "22")
            String fromPort,
            @Schema(description = "끝 포트", example = "22")
            String toPort,
            @Schema(description = "프로토콜", example = "tcp", allowableValues = {"tcp", "udp", "icmp", "-1"})
            String ipProtocol,
            @Schema(description = "방향", example = "inbound", allowableValues = {"inbound", "outbound"})
            String direction,
            @Schema(description = "허용 CIDR", example = "0.0.0.0/0")
            String cidr
    ) {}

    @Schema(description = "보안그룹 규칙 추가 요청")
    public record AddSecurityRulesRequest(
            @Schema(description = "클라우드 연결 설정명", example = "aws-seoul-conn", requiredMode = Schema.RequiredMode.REQUIRED)
            String connectionName,
            @Schema(description = "추가할 규칙 목록", requiredMode = Schema.RequiredMode.REQUIRED)
            List<SecurityRule> rules
    ) {}

    @Schema(description = "키페어 생성 요청")
    public record CreateKeyPairRequest(
            @Schema(description = "클라우드 연결 설정명", example = "aws-seoul-conn", requiredMode = Schema.RequiredMode.REQUIRED)
            String connectionName,
            @Schema(description = "키페어 정보", requiredMode = Schema.RequiredMode.REQUIRED)
            KeyPairReqInfo reqInfo
    ) {}

    @Schema(description = "키페어 정보")
    public record KeyPairReqInfo(
            @Schema(description = "키페어 이름", example = "aims-keypair-01", requiredMode = Schema.RequiredMode.REQUIRED)
            String name
    ) {}

    // ── 이미지/스냅샷 ───────────────────────────────────────────

    @Schema(description = "스냅샷(MyImage) 생성 요청")
    public record CreateSnapshotRequest(
            @Schema(description = "클라우드 연결 설정명", example = "aws-seoul-conn", requiredMode = Schema.RequiredMode.REQUIRED)
            String connectionName,
            @Schema(description = "스냅샷 정보", requiredMode = Schema.RequiredMode.REQUIRED)
            SnapshotReqInfo reqInfo
    ) {}

    @Schema(description = "스냅샷 상세 정보")
    public record SnapshotReqInfo(
            @Schema(description = "스냅샷 이름", example = "aims-snapshot-01", requiredMode = Schema.RequiredMode.REQUIRED)
            String name,
            @Schema(description = "소스 VM 이름", example = "aims-vm-01", requiredMode = Schema.RequiredMode.REQUIRED)
            String sourceVM
    ) {}

    // ── 태그 ────────────────────────────────────────────────────

    @Schema(description = "레이블(태그) 설정 요청")
    public record SetLabelRequest(
            @Schema(description = "레이블 키", example = "env", requiredMode = Schema.RequiredMode.REQUIRED)
            String key,
            @Schema(description = "레이블 값", example = "production", requiredMode = Schema.RequiredMode.REQUIRED)
            String value
    ) {}

    private ResourceDto() {}
}
