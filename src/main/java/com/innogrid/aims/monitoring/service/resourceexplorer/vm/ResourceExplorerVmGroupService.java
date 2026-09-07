package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.util.List;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vmgroup.VmGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vmgroup.VmListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vmgroup.VmTopResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 자원 탐색기 가상머신 그룹 패널 조회 서비스.
 * 트리에서 배포영역별 가상머신 그룹 선택 시 개요 탭의 KPI 카드·VM Top-N·VM 목록을 제공한다.
 * (알람 탭은 기존 AlarmController 를 자원 스코프 필터로 재사용한다)
 * MDC 패널은 ResourceExplorerMdcService, 트리는 ResourceExplorerTreeService 가 담당한다.
 */
@Service
public class ResourceExplorerVmGroupService {

    /** 가상머신 그룹 KPI 카드 — 가상머신 수 / Running / Stop / Error */
    public VmGroupSummaryResponse getVmGroupSummary(String roleName, String deploymentId) {
        // TODO:: VM 원장(OpenStack Nova 연동) 확정 후 상태별 집계 구현
        throw dataSourceNotImplemented();
    }

    /** 가상머신 그룹 내 VM Top-N — 요청 지표 내림차순, CPU/MEM/FS 보조 지표 동봉 */
    public List<VmTopResponse> getVmGroupTop(String roleName, String deploymentId,
                                             NodeMetric metric, int limit) {
        // TODO:: VM 내부 지표(VM 내 node_exporter) 수집 체계 확정 후 metric 기준 상위 N개 집계 구현
        throw dataSourceNotImplemented();
    }

    /** 가상머신 그룹 내 VM 목록 — 상태·키워드 필터, 컬럼 정렬, 페이징(1부터) */
    public PageResponse<VmListItemResponse> getVmGroupVms(String roleName, String deploymentId,
                                                          NodeStatus status, String keyword,
                                                          int page, int size, String sort) {
        // TODO:: VM 원장과 VM 내부 지표를 병합해 목록 구성 (sort: "{필드},{asc|desc}")
        throw dataSourceNotImplemented();
    }

    private ResponseStatusException dataSourceNotImplemented() {
        return new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "VM 지표 데이터와 자원 탐색기 가상머신 그룹 패널의 매핑 구현 전입니다.");
    }
}
