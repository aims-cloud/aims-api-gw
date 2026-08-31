package com.innogrid.aims.monitoring.service;

import java.time.Instant;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.cpu.VmCpuListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.cpu.VmCpuMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.cpu.VmCpuMetricTrendResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 자원 탐색기 VM CPU 카테고리 패널 조회 서비스.
 * 트리에서 VM 하위 CPU 항목 선택 시 개요(KPI 카드·CPU 목록)와 모니터링(mode 별 차트 9종) 탭 데이터를 제공한다.
 * KPI 카드 4종은 노드 CPU 와 동일 구조라 CpuSummaryResponse 를 재사용한다.
 * VM 상세 패널은 ResourceExplorerVmService, 노드 CPU 는 ResourceExplorerNodeCpuService 가 담당한다.
 */
@Service
public class ResourceExplorerVmCpuService {

    /** 개요 탭 KPI 카드 4종 — Used / I/O Wait / System / Steal (%) */
    public CpuSummaryResponse getCpuSummary(String vmId) {
        // TODO:: VM 내부 지표(VM 내 node_exporter node_cpu_seconds_total) mode 별 비율 계산으로 카드 구성
        throw dataSourceNotImplemented();
    }

    /** 개요 탭 CPU 목록 — 소켓/벤더/모델/코어·스레드/수집 상태, 페이징(1부터)·컬럼 정렬 */
    public PageResponse<VmCpuListItemResponse> getCpuDevices(String vmId,
                                                             int page, int size, String sort) {
        // TODO:: VM 내부 node_cpu_info 레이블과 수집 메타를 병합해 목록 구성
        throw dataSourceNotImplemented();
    }

    /** 모니터링 탭 차트 — CPU mode 별 시계열 9종 */
    public VmCpuMetricTrendResponse getCpuMetricTrend(String vmId, VmCpuMetric metric,
                                                      Instant from, Instant to, String step) {
        // TODO:: Thanos range 쿼리로 mode 별 시계열 구성 (참고 설계 기준 — AIMS 화면 확정 시 지표 세트 조정)
        throw dataSourceNotImplemented();
    }

    private ResponseStatusException dataSourceNotImplemented() {
        return new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "VM CPU 지표 데이터와 자원 탐색기 VM CPU 패널의 매핑 구현 전입니다.");
    }
}
