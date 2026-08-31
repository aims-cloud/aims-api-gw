package com.innogrid.aims.monitoring.service.resourceexplorer;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.root.RoleListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.root.RootSummaryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 자원 탐색기 전체 자원(트리 루트) 패널 조회 서비스.
 * KPI 카드 8종, 노드/가상머신 상태 도넛, 역할 테이블을 제공한다.
 * (참고 설계(TabCLOUD) 기준 — AIMS 화면 확정 시 위젯 구성 조정 가능)
 * 역할 패널은 ResourceExplorerRoleService, 트리는 ResourceExplorerTreeService 가 담당한다.
 */
@Service
public class ResourceExplorerRootService {

    /** KPI 카드 8종 — 역할/배포영역/노드/가상머신 수, Running/Error/Pending/Stop(노드+VM 합산) */
    public RootSummaryResponse getRootSummary() {
        // TODO:: 역할·노드·VM 원장 확정 후 전체 집계 구현
        throw dataSourceNotImplemented();
    }

    /** 노드 상태 도넛 — 총계(하이퍼바이저 수)와 상태별 카운트 */
    public ResourceStatusDistributionResponse getNodeStatus() {
        // TODO:: 노드 원장 확정 후 전체 스코프 상태 집계 구현
        throw dataSourceNotImplemented();
    }

    /** 가상머신 상태 도넛 — 총계와 상태별 카운트 */
    public ResourceStatusDistributionResponse getVmStatus() {
        // TODO:: VM 원장 확정 후 전체 스코프 상태 집계 구현
        throw dataSourceNotImplemented();
    }

    /** 역할 테이블 — 관리자/멤버·배포영역·노드·VM 수/상태 스택바, 키워드 검색·컬럼 정렬·페이징(1부터) */
    public PageResponse<RoleListItemResponse> getRoles(String keyword, int page, int size, String sort) {
        // TODO:: 역할 원장(관리자·멤버 포함) 확정 후 역할별 자원·상태 집계 구현
        throw dataSourceNotImplemented();
    }

    private ResponseStatusException dataSourceNotImplemented() {
        return new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "전체 자원 집계 데이터와 자원 탐색기 전체 자원 패널의 매핑 구현 전입니다.");
    }
}
