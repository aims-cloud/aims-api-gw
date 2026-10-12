package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuDeviceSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu.VmGpuAllocationResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu.VmGpuGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu.VmGpuInstanceInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu.VmGpuInstanceListItemResponse;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 VM GPU 카테고리 패널 조회 서비스.
 * 그룹(VM 에 할당된 GPU 인스턴스 전체)과 개별 인스턴스의 개요·모니터링 탭 데이터를 함께 제공한다.
 * 개별 KPI 카드는 노드 GPU 와 동일 구조라 GpuDeviceSummaryResponse 를 재사용한다.
 *
 * VM 식별(nsId·infraId·vmId)과 조회 기간(interval)은 다른 VM 카테고리와 통일했으나,
 * VM↔GPU 인스턴스 할당 원장이 없어 데이터 구현은 후순위(501)다. VM 안 dcgm_exporter 지표 자체는
 * {@code tb_*} 라벨로 읽을 수 있으므로 원장 확정 시 CPU 패턴대로 HostCategoryMetricsQueryService 를 확장한다.
 * VM 상세 패널은 ResourceExplorerVmService, 노드 GPU 는 ResourceExplorerNodeGpuService 가 담당한다.
 */
@Service
public class ResourceExplorerVmGpuService {

    private final TumblebugPanelSupport support;

    public ResourceExplorerVmGpuService(TumblebugPanelSupport support) {
        this.support = support;
    }

    // ── 그룹 (트리에서 VM 하위 GPU 카테고리 선택) ───────────────

    /** 그룹 개요 KPI 카드 6종 — 총 GPU 수 / 최대·평균 GPU Util / 평균·최대 GPU Mem Util / 최근 알람 수 */
    public VmGpuGroupSummaryResponse getGroupSummary(String roleName, String nsId, String infraId, String vmId) {
        support.accessibleNode(roleName, nsId, infraId, vmId);
        // TODO:: VM↔GPU 인스턴스 할당 원장 확정 후 DCGM 지표(tb_* 라벨) 집계로 카드 구성
        throw dataSourceNotImplemented();
    }

    /** 그룹 개요 GPU 인스턴스 목록 — 타입/현재·평균 Util/VRAM(사용·할당)/수집 상태/최근 알람 수 */
    public PageResponse<VmGpuInstanceListItemResponse> getInstances(String roleName, String nsId, String infraId, String vmId,
                                                                    int page, int size, String sort) {
        support.accessibleNode(roleName, nsId, infraId, vmId);
        // TODO:: 할당 원장 + DCGM 지표(현재값·avg_over_time) + AlarmHistory 병합해 목록 구성
        throw dataSourceNotImplemented();
    }

    /** 그룹 모니터링 차트 — 인스턴스별 분해 시계열 */
    public GpuMetricTrendResponse getGroupMetricTrend(String roleName, String nsId, String infraId, String vmId,
                                                      GpuMetric metric, NodeMetricInterval interval) {
        support.accessibleNode(roleName, nsId, infraId, vmId);
        // TODO:: 모니터링 탭 화면 설계 확정 후 지표 세트 조정
        throw dataSourceNotImplemented();
    }

    // ── 개별 GPU 인스턴스 (트리에서 GPU 장치 선택) ──────────────

    /** 인스턴스 개요 KPI 카드 6종 — Util / Mem Util / VRAM 사용·할당 / Power / 온도 / Fan */
    public GpuDeviceSummaryResponse getInstanceSummary(String roleName, String nsId, String infraId, String vmId,
                                                       String instanceId) {
        support.accessibleNode(roleName, nsId, infraId, vmId);
        // TODO:: DCGM 지표(인스턴스 필터)로 카드 구성 (화면의 "RAM Used/Total" 라벨은 VRAM 오기로 처리)
        throw dataSourceNotImplemented();
    }

    /** 인스턴스 가상머신 할당 정보 표 — 인스턴스/VM/타입/매핑 물리 GPU/VRAM 할당·사용/라이선스 상태 */
    public VmGpuAllocationResponse getInstanceAllocation(String roleName, String nsId, String infraId, String vmId,
                                                         String instanceId) {
        support.accessibleNode(roleName, nsId, infraId, vmId);
        // TODO:: 가상화 계층(하이퍼바이저) 할당 정보 원천 확정 후 구현
        throw dataSourceNotImplemented();
    }

    /** 인스턴스 GPU 기본 정보 표 — 모델/UUID/CUDA·드라이버 버전/수집 상태/마지막 수집 */
    public VmGpuInstanceInfoResponse getInstanceInfo(String roleName, String nsId, String infraId, String vmId,
                                                     String instanceId) {
        support.accessibleNode(roleName, nsId, infraId, vmId);
        // TODO:: DCGM 레이블·필드와 수집 메타를 병합해 구성
        throw dataSourceNotImplemented();
    }

    /** 인스턴스 모니터링 차트 — 해당 인스턴스 시계열 */
    public GpuMetricTrendResponse getInstanceMetricTrend(String roleName, String nsId, String infraId, String vmId,
                                                         String instanceId, GpuMetric metric, NodeMetricInterval interval) {
        support.accessibleNode(roleName, nsId, infraId, vmId);
        // TODO:: 모니터링 탭 화면 설계 확정 후 지표 세트 조정
        throw dataSourceNotImplemented();
    }

    private static BusinessException dataSourceNotImplemented() {
        return BusinessException.notImplemented("GPU 인스턴스 데이터와 자원 탐색기 VM GPU 패널의 매핑 구현 전입니다.");
    }
}
