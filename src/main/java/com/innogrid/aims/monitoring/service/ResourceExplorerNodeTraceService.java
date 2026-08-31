package com.innogrid.aims.monitoring.service;

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
import org.springframework.web.server.ResponseStatusException;

/**
 * 자원 탐색기 노드 로그/트레이스 탭 — 서비스 트레이스 조회 서비스.
 * 트레이스 저장소(Tempo/Jaeger 등)와 계측 방식은 미확정 — 확정 후 연동 구현한다.
 * 수집 서비스 관리·로그는 ResourceExplorerNodeLogService 가 담당한다.
 */
@Service
public class ResourceExplorerNodeTraceService {

    /** 트레이스 KPI 카드 4종 — 수집 상태 / 총 요청 수 / 요청률 / 평균 응답 */
    public ServiceTraceSummaryResponse getTraceSummary(String nodeId, String serviceId,
                                                       Instant from, Instant to) {
        throw dataSourceNotImplemented();
    }

    /** 요청량 추이 — 버킷별 요청 수 */
    public ServiceTraceTrendResponse getTraceTrend(String nodeId, String serviceId,
                                                   Instant from, Instant to, String interval) {
        throw dataSourceNotImplemented();
    }

    /** 메서드 분포 — 총 요청 수 + 메서드별 건수 */
    public TraceMethodDistributionResponse getMethodDistribution(String nodeId, String serviceId,
                                                                 Instant from, Instant to) {
        throw dataSourceNotImplemented();
    }

    /** 엔드포인트 상위 목록 — 메서드별 상위 N개 */
    public List<TraceEndpointStatResponse> getTopEndpoints(String nodeId, String serviceId,
                                                           Instant from, Instant to, int limit) {
        throw dataSourceNotImplemented();
    }

    /** 관측된 트레이스 포트 목록 — 상세 페이지 좌측 체크박스 */
    public List<Integer> getTracePorts(String nodeId, String serviceId) {
        throw dataSourceNotImplemented();
    }

    /** 트레이스 목록/검색 — 포트·상태 클래스(2XX/4XX/5XX)·키워드·최소 소요 필터, 시간 역순 */
    public TraceListResponse searchTraces(String nodeId, String serviceId, List<Integer> ports,
                                          List<String> statusClasses, String keyword,
                                          Integer minDurationMs, Instant from, Instant to, int limit) {
        throw dataSourceNotImplemented();
    }

    /** 트레이스 상세 — 요약 카드와 스팬 워터폴 */
    public TraceDetailResponse getTraceDetail(String nodeId, String serviceId, String traceId) {
        throw dataSourceNotImplemented();
    }

    /** 트레이스 관련 로그 — 트레이스 시간창 기준 (traceId 정확 매칭은 로그 계측 확정 후) */
    public ServiceLogListResponse getTraceLogs(String nodeId, String serviceId, String traceId,
                                               List<String> files) {
        throw dataSourceNotImplemented();
    }

    private ResponseStatusException dataSourceNotImplemented() {
        return new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "트레이스 저장소·계측 방식 확정 전 — 자원 탐색기 트레이스 패널 매핑 구현 전입니다.");
    }
}
