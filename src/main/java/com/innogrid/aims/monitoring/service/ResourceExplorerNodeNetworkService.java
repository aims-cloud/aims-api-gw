package com.innogrid.aims.monitoring.service;

import java.time.Instant;

import com.innogrid.aims.monitoring.dto.resourceexplorer.node.network.NetworkMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.network.NetworkMetricTrendResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 자원 탐색기 노드 Network 카테고리 패널 조회 서비스.
 * 트리에서 노드 하위 Network 항목 선택 시 모니터링 탭 차트 데이터를 제공한다.
 * (개요 탭은 화면 설계 미확정 — 추가 시 CPU 패턴대로 summary/devices 를 확장한다)
 * 노드 상세 패널은 ResourceExplorerNodeService 가 담당한다.
 */
@Service
public class ResourceExplorerNodeNetworkService {

    /** 모니터링 탭 차트 — 지표별 시계열 (물리 NIC 합산, lo·가상 인터페이스 제외) */
    public NetworkMetricTrendResponse getNetworkMetricTrend(String nodeId, NetworkMetric metric,
                                                            Instant from, Instant to, String step) {
        // TODO:: Thanos(node_network_*_total irate) range 쿼리로 시계열 구성
        //        인터페이스 제외 패턴(device!~"lo|veth.*|br.*|docker.*")은 테스트베드 확인 후 확정
        throw dataSourceNotImplemented();
    }

    private ResponseStatusException dataSourceNotImplemented() {
        return new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "Network 지표 데이터와 자원 탐색기 Network 패널의 매핑 구현 전입니다.");
    }
}
