package com.innogrid.aims.monitoring.service.resourceexplorer;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleSummaryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 자원 탐색기 역할 패널 조회 서비스.
 * 트리에서 역할 선택 시 KPI 카드 8종, 노드/가상머신 상태 위젯, 배포영역 노드 목록을 제공한다.
 * 왼쪽 트리는 ResourceExplorerTreeService, 노드그룹/VM그룹 패널은 각 그룹 서비스가 담당한다.
 */
@Service
public class ResourceExplorerRoleService {

    /** KPI 카드 8종 — 멤버/배포영역/노드/가상머신 수, Running/Pending/Stop/Error */
    public RoleSummaryResponse getRoleSummary(String roleId) {
        // TODO:: 역할 원장(멤버 포함)과 노드·VM 원장 확정 후 집계 구현
        // TODO:: 상태 카운트의 집계 대상(노드+VM 합산 여부)과 Pending 상태 정의 확정 필요
        throw dataSourceNotImplemented();
    }

    /** 노드 상태 위젯 — 상태별 분포 도넛 (전체 자원 패널과 동일 구조) */
    public ResourceStatusDistributionResponse getNodeStatus(String roleId) {
        // TODO:: 노드 원장 확정 후 역할 스코프 상태 집계 구현
        throw dataSourceNotImplemented();
    }

    /** 가상머신 상태 위젯 — 상태별 분포 도넛 (전체 자원 패널과 동일 구조) */
    public ResourceStatusDistributionResponse getVmStatus(String roleId) {
        // TODO:: VM 원장 확정 후 역할 스코프 상태 집계 구현
        throw dataSourceNotImplemented();
    }

    /** 배포영역 섹션 노드 목록 — 상태·배포영역·키워드 필터, 컬럼 정렬, 페이징(1부터) */
    public PageResponse<NodeListItemResponse> getRoleNodes(String roleId, String deploymentId,
                                                           NodeStatus status, String keyword,
                                                           int page, int size, String sort) {
        // TODO:: 섹션명이 '배포영역'인데 내용이 노드 목록 — 표시 의도 디자인 확인 필요
        throw dataSourceNotImplemented();
    }

    private ResponseStatusException dataSourceNotImplemented() {
        return new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "역할 스코프 자원 데이터와 자원 탐색기 역할 패널의 매핑 구현 전입니다.");
    }
}
