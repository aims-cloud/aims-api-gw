package com.innogrid.aims.monitoring.service;

import java.util.List;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.NodeGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.NodeListItemResponse;
import com.innogrid.aims.monitoring.dto.NodeMetric;
import com.innogrid.aims.monitoring.dto.NodeStatus;
import com.innogrid.aims.monitoring.dto.NodeTopResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 자원 탐색기 노드그룹 패널 조회 서비스.
 * 트리에서 배포영역별 노드그룹 선택 시 개요 탭의 KPI 카드·노드 Top-N·노드 목록을 제공한다.
 * 왼쪽 트리 구성은 ResourceExplorerTreeService 가 담당한다.
 */
@Service
public class ResourceExplorerNodeGroupService {

    /** 노드그룹 KPI 카드 — 노드 수 / Running / Stop / Error */
    public NodeGroupSummaryResponse getNodeGroupSummary(String roleId, String deploymentId) {
        // TODO:: 노드 상태(Running/Stop/Error) 판정 기준 확정 후 상태별 집계 구현
        throw dataSourceNotImplemented();
    }

    /** 노드그룹 내 노드 Top-N — 요청 지표 내림차순, CPU/MEM/FS 보조 지표 동봉 */
    public List<NodeTopResponse> getNodeGroupTop(String roleId, String deploymentId,
                                                 NodeMetric metric, int limit) {
        // TODO:: Thanos(node_exporter) 지표 조회 후 metric 기준 상위 N개 집계 구현
        throw dataSourceNotImplemented();
    }

    /** 노드그룹 내 노드 목록 — 상태·키워드 필터, 컬럼 정렬, 페이징(1부터) */
    public PageResponse<NodeListItemResponse> getNodeGroupNodes(String roleId, String deploymentId,
                                                                NodeStatus status, String keyword,
                                                                int page, int size, String sort) {
        // TODO:: 수집 대상 원장과 Thanos 지표를 병합해 목록 구성 (sort: "{필드},{asc|desc}")
        throw dataSourceNotImplemented();
    }

    private ResponseStatusException dataSourceNotImplemented() {
        return new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "노드 지표 데이터와 자원 탐색기 노드그룹 패널의 매핑 구현 전입니다.");
    }
}
