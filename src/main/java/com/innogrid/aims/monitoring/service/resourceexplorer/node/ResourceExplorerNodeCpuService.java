package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuDeviceListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuSummaryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 자원 탐색기 노드 CPU 카테고리 패널 조회 서비스.
 * 트리에서 노드 하위 CPU 항목 선택 시 개요(KPI 카드·CPU 목록)와 모니터링(차트) 탭 데이터를 제공한다.
 * 노드 상세 패널은 ResourceExplorerNodeService 가 담당한다.
 */
@Service
public class ResourceExplorerNodeCpuService {

    /** 개요 탭 KPI 카드 4종 — Used / I/O Wait / System / Steal (%) */
    public CpuSummaryResponse getCpuSummary(String nodeId) {
        // TODO:: Thanos(node_cpu_seconds_total) mode 별 비율 계산으로 카드 구성
        throw dataSourceNotImplemented();
    }

    /** 개요 탭 CPU 목록 — 소켓/벤더/모델/코어·스레드/클럭, 페이징(1부터)·컬럼 정렬 */
    public PageResponse<CpuDeviceListItemResponse> getCpuDevices(String nodeId,
                                                                 int page, int size, String sort) {
        // TODO:: node_cpu_info 레이블과 수집 메타(수집 상태/마지막 수집)를 병합해 목록 구성
        throw dataSourceNotImplemented();
    }

    /** 모니터링 탭 차트 — 지표별 시계열 (READINESS 는 물리 노드 미지원) */
    public CpuMetricTrendResponse getCpuMetricTrend(String nodeId, CpuMetric metric,
                                                    Instant from, Instant to, String step) {
        // TODO:: Thanos range 쿼리로 지표별 시계열 구성, 미지원 지표(READINESS) 처리 방침 확정
        throw dataSourceNotImplemented();
    }

    private ResponseStatusException dataSourceNotImplemented() {
        return new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "CPU 지표 데이터와 자원 탐색기 CPU 패널의 매핑 구현 전입니다.");
    }
}
