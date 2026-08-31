package com.innogrid.aims.monitoring.service;

import java.util.List;

import com.innogrid.aims.monitoring.dto.ResourceTreeFilter;
import com.innogrid.aims.monitoring.dto.ResourceTreeNodeResponse;
import com.innogrid.aims.monitoring.dto.ResourceTreeNodeType;
import com.innogrid.aims.monitoring.dto.ResourceTreeSearchResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 자원 탐색기 왼쪽 트리 구성 서비스.
 * 노드그룹 패널(개요 KPI·Top-N·목록)은 ResourceExplorerNodeGroupService 가 담당한다.
 */
@Service
public class ResourceExplorerTreeService {

    /** ROOT부터 모든 하위 계층을 포함한 전체 트리를 구성한다. */
    public ResourceTreeNodeResponse getTree(ResourceTreeFilter filter) {
        // TODO:: 역할·배포영역·물리 호스트·VM 관계를 자원 관리 데이터에서 조회하여 전체 트리 구성
        throw dataSourceNotImplemented();
    }

    /** Lazy Loading을 위한 ROLE 계층을 조회한다. */
    public List<ResourceTreeNodeResponse> getRoots(ResourceTreeFilter filter) {
        // TODO:: 자원 관리 데이터에서 역할 목록과 역할별 직계 하위 자원 수 조회
        throw dataSourceNotImplemented();
    }

    /** 선택된 항목 유형에 따라 직계 하위 계층만 조회한다. */
    public List<ResourceTreeNodeResponse> getChildren(String treeNodeId) {
        // TODO:: treeNodeId를 안정적인 자원 식별자로 해석하고 유형별 하위 자원 조회
        throw dataSourceNotImplemented();
    }

    /** 이름으로 자원을 검색하고 ROOT부터 검색 결과까지의 경로를 반환한다. */
    public List<ResourceTreeSearchResponse> search(
            String keyword, List<ResourceTreeNodeType> types, int limit) {
        // TODO:: 자원 관리 데이터 검색 및 각 결과의 역할·배포영역 상위 경로 구성
        throw dataSourceNotImplemented();
    }

    private ResponseStatusException dataSourceNotImplemented() {
        return new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "자원 관리 데이터와 자원 탐색기 트리의 매핑 구현 전입니다.");
    }
}
