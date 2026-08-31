package com.innogrid.aims.monitoring.service;

import java.time.Instant;

import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.network.VmNetworkMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.network.VmNetworkMetricTrendResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 자원 탐색기 VM Network 카테고리 패널 조회 서비스.
 * 트리에서 VM 하위 Network 항목 선택 시 모니터링 탭 차트 데이터를 제공한다.
 * (개요 탭은 화면 설계 미확정 — 추가 시 CPU 패턴대로 확장한다)
 * VM 상세 패널은 ResourceExplorerVmService, 노드 Network 는 ResourceExplorerNodeNetworkService 가 담당한다.
 */
@Service
public class ResourceExplorerVmNetworkService {

    /** 모니터링 탭 차트 — 지표별 시계열 8종 (VM NIC 합산) */
    public VmNetworkMetricTrendResponse getNetworkMetricTrend(String vmId, VmNetworkMetric metric,
                                                              Instant from, Instant to, String step) {
        // TODO:: VM 내부 지표(VM 내 node_exporter node_network_*_total irate) range 쿼리로 시계열 구성
        throw dataSourceNotImplemented();
    }

    private ResponseStatusException dataSourceNotImplemented() {
        return new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "VM Network 지표 데이터와 자원 탐색기 VM Network 패널의 매핑 구현 전입니다.");
    }
}
