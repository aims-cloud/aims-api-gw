package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.util.List;

import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogListResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.trace.ServiceTraceSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.trace.ServiceTraceTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.trace.TraceDetailResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.trace.TraceEndpointStatResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.trace.TraceListResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.trace.TraceMethodDistributionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.innogrid.aims.common.exception.BusinessException;

/**
 * 자원 탐색기 노드 로그/트레이스 탭 — 서비스 트레이스 조회 서비스.
 * 2026-09-30 범위 제외: 트레이스는 협약 계획서·요구사항 정의서·API 명세서·갭 분석 어디에도 없는 화면(피그마) 유래 기능이라
 * 이번 연차 범위에서 뺐다. 접근 검증 후 501 을 유지하고, 재개 시 Confluence 설계 문서 §9(Beyla eBPF → Tempo)를 기준으로 구현한다.
 * 수집 서비스 관리·로그는 ResourceExplorerNodeLogService 가 담당한다.
 *
 * @deprecated 범위 제외(2026-09-30). 경로·DTO 계약은 프론트 호환을 위해 유지한다.
 */
@Deprecated
@Service
public class ResourceExplorerNodeTraceService {
    private final ResourceExplorerNodeLogService services;

    public ResourceExplorerNodeTraceService(ResourceExplorerNodeLogService services) {
        this.services = services;
    }

    /** 트레이스 KPI 카드 4종 — 수집 상태 / 총 요청 수 / 요청률 / 평균 응답 */
    public ServiceTraceSummaryResponse getTraceSummary(String nodeId, String serviceId,
                                                       Instant from, Instant to) {
        throw dataSourceNotImplemented(nodeId, serviceId);
    }

    /** 요청량 추이 — 버킷별 요청 수 */
    public ServiceTraceTrendResponse getTraceTrend(String nodeId, String serviceId,
                                                   Instant from, Instant to, String interval) {
        throw dataSourceNotImplemented(nodeId, serviceId);
    }

    /** 메서드 분포 — 총 요청 수 + 메서드별 건수 */
    public TraceMethodDistributionResponse getMethodDistribution(String nodeId, String serviceId,
                                                                 Instant from, Instant to) {
        throw dataSourceNotImplemented(nodeId, serviceId);
    }

    /** 엔드포인트 상위 목록 — 메서드별 상위 N개 */
    public List<TraceEndpointStatResponse> getTopEndpoints(String nodeId, String serviceId,
                                                           Instant from, Instant to, int limit) {
        throw dataSourceNotImplemented(nodeId, serviceId);
    }

    /** 관측된 트레이스 포트 목록 — 상세 페이지 좌측 체크박스 */
    public List<Integer> getTracePorts(String nodeId, String serviceId) {
        throw dataSourceNotImplemented(nodeId, serviceId);
    }

    /** 트레이스 목록/검색 — 포트·상태 클래스(2XX/4XX/5XX)·키워드·최소 소요 필터, 시간 역순 */
    public TraceListResponse searchTraces(String nodeId, String serviceId, List<Integer> ports,
                                          List<String> statusClasses, String keyword,
                                          Integer minDurationMs, Instant from, Instant to, int limit) {
        throw dataSourceNotImplemented(nodeId, serviceId);
    }

    /** 트레이스 상세 — 요약 카드와 스팬 워터폴 */
    public TraceDetailResponse getTraceDetail(String nodeId, String serviceId, String traceId) {
        throw dataSourceNotImplemented(nodeId, serviceId);
    }

    /** 트레이스 관련 로그 — 트레이스 시간창 기준 (traceId 정확 매칭은 로그 계측 확정 후) */
    public ServiceLogListResponse getTraceLogs(String nodeId, String serviceId, String traceId,
                                               List<String> files) {
        throw dataSourceNotImplemented(nodeId, serviceId);
    }

    private BusinessException dataSourceNotImplemented(String nodeId, String serviceId) {
        services.requireAccessibleService(nodeId, serviceId);
        return new BusinessException(
                HttpStatus.NOT_IMPLEMENTED,
                "트레이스 기능은 이번 연차 범위에서 제외되었습니다(2026-09-30). 계획서·요구사항에 없는 기능으로 501 을 유지합니다.");
    }
}
