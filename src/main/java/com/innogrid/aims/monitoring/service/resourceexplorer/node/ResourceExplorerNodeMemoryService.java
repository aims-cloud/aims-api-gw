package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;

import com.innogrid.aims.monitoring.dto.resourceexplorer.node.memory.MemoryMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.memory.MemoryMetricTrendResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 자원 탐색기 노드 Memory 카테고리 패널 조회 서비스.
 * 트리에서 노드 하위 Memory 항목 선택 시 모니터링 탭 차트 데이터를 제공한다.
 * (개요 탭은 화면 설계 미확정 — 추가 시 CPU 패턴대로 summary/devices 를 확장한다)
 * 노드 상세 패널은 ResourceExplorerNodeService 가 담당한다.
 */
@Service
public class ResourceExplorerNodeMemoryService {

    /** 모니터링 탭 차트 — 지표별 시계열 (Free 는 MemAvailable 기준) */
    public MemoryMetricTrendResponse getMemoryMetricTrend(String nodeId, MemoryMetric metric,
                                                          Instant from, Instant to, String step) {
        // TODO:: Thanos(node_memory_MemTotal_bytes·MemAvailable_bytes) range 쿼리로 시계열 구성
        throw dataSourceNotImplemented();
    }

    private ResponseStatusException dataSourceNotImplemented() {
        return new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "Memory 지표 데이터와 자원 탐색기 Memory 패널의 매핑 구현 전입니다.");
    }
}
