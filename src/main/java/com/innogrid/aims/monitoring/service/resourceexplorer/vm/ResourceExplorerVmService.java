package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.time.Instant;

import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeDetailMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.VmInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.VmSummaryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 자원 탐색기 VM 상세 패널 조회 서비스.
 * 트리에서 가상머신 선택 시 개요(주요 지표 카드·모니터링 요약 차트·VM 정보 표)와 모니터링 탭 데이터를 제공한다.
 * 모니터링 차트 지표는 노드 상세와 동일 6종이라 NodeDetailMetric·NodeMetricTrendResponse 를 재사용한다.
 * NodeGroup 패널은 ResourceExplorerNodeGroupService 가 담당한다.
 */
@Service
public class ResourceExplorerVmService {

    /** 개요 탭 주요 지표 카드 5종 — CPU/MEM/FS/네트워크/GPU(VRAM Free·Total 병기) */
    public VmSummaryResponse getVmSummary(String vmId) {
        // TODO:: VM 내부 지표(VM 내 node_exporter) 수집 체계 확정 후 카드 구성
        throw dataSourceNotImplemented();
    }

    /** 모니터링 요약 차트 — 지표별 시계열 6종 (NETWORK 는 수신/송신 2개 시리즈) */
    public NodeMetricTrendResponse getVmMetricTrend(String vmId, NodeDetailMetric metric,
                                                    Instant from, Instant to, String step) {
        // TODO:: VM 스코프 레이블로 Thanos range 쿼리 구성 (개요·모니터링 탭 공용)
        throw dataSourceNotImplemented();
    }

    /** 개요 탭 VM 정보 표 — ID/이름/배포영역/프로젝트/IP/사양/네트워크/노드명/이미지/카탈로그/시스템 상태 */
    public VmInfoResponse getVmInfo(String vmId) {
        // TODO:: VM 원장(OpenStack Nova 연동) 확정 후 조회 구현
        throw dataSourceNotImplemented();
    }

    private ResponseStatusException dataSourceNotImplemented() {
        return new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "VM 지표·정보 데이터와 자원 탐색기 VM 상세 패널의 매핑 구현 전입니다.");
    }
}
