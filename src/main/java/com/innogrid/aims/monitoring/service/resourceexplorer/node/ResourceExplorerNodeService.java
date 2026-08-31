package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.util.List;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeDetailMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeVmListItemResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 자원 탐색기 노드 상세 패널 조회 서비스.
 * 트리에서 물리 노드 선택 시 개요·가상머신·자원 사용 현황 탭 데이터를 제공한다.
 * (알람·로그 탭은 기존 AlarmController / LogController 를 instance 필터로 재사용한다)
 * 노드그룹 패널은 ResourceExplorerNodeGroupService, 트리는 ResourceExplorerTreeService 가 담당한다.
 */
@Service
public class ResourceExplorerNodeService {

    /** 개요 탭 주요 지표 카드 6종 — CPU/MEM/FS/네트워크/GPU/VRAM */
    public NodeSummaryResponse getNodeSummary(String nodeId) {
        // TODO:: Thanos(node_exporter·dcgm_exporter) 실시간 지표 조회 후 카드 구성
        throw dataSourceNotImplemented();
    }

    /** 개요 탭 모니터링 요약 차트 — 지표별 시계열 (NETWORK 는 수신/송신 2개 시리즈) */
    public NodeMetricTrendResponse getNodeMetricTrend(String nodeId, NodeDetailMetric metric,
                                                      Instant from, Instant to, String step) {
        // TODO:: Thanos range 쿼리로 지표별 시계열 구성 (기간 드롭다운 공통 적용)
        throw dataSourceNotImplemented();
    }

    /** 개요 탭 노드 정보 표 — 자원 탐색기 자체 원장 기준 */
    public NodeInfoResponse getNodeInfo(String nodeId) {
        // TODO:: 노드 원장(이름/ID/IP/유형/할당량/스토리지/프로바이더) 재정의 후 조회 구현
        //        가동일수는 node_boot_time_seconds 로 계산
        throw dataSourceNotImplemented();
    }

    /** 가상머신 탭 — 이 노드에서 실행 중인 VM 목록 (페이징 1부터) */
    public PageResponse<NodeVmListItemResponse> getNodeVms(String nodeId, NodeStatus status,
                                                           String keyword, int page, int size, String sort) {
        // TODO:: 노드↔VM 매핑 원장 확정 후 목록 구현 (화면 설계 확정 시 컬럼 재정의)
        throw dataSourceNotImplemented();
    }

    /** 자원 사용 현황 탭 — 카테고리별 상세 시계열 */
    public List<NodeMetricTrendResponse> getNodeUsage(String nodeId, String category,
                                                      Instant from, Instant to) {
        // TODO:: 자원 사용 현황 탭 화면 설계 확정 후 카테고리·응답 구조 재정의
        throw dataSourceNotImplemented();
    }

    private ResponseStatusException dataSourceNotImplemented() {
        return new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "노드 지표·정보 데이터와 자원 탐색기 노드 상세 패널의 매핑 구현 전입니다.");
    }
}

