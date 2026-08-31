package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.util.List;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogFileResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogLevel;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogListResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceRequest;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceValidationResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.DiscoveredServiceResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 자원 탐색기 노드 로그/트레이스 탭 조회·관리 서비스 — 수집 서비스 CRUD 와 서비스 로그.
 * 상태·PID 는 노드 에이전트 관측 정보이고 로그 저장소는 Loki 를 서비스·파일 레이블로 확장해 사용한다.
 * 노드 상세 개요·가상머신 탭은 ResourceExplorerNodeService 가 담당한다.
 */
@Service
public class ResourceExplorerNodeLogService {

    // ── 수집 서비스 관리 ────────────────────────────────────────

    /** 수집 서비스 목록 — 서비스명/상태/포트/PID/설명, 키워드 검색·컬럼 정렬·페이징(1부터) */
    public PageResponse<CollectServiceResponse> getCollectServices(String nodeId, String keyword,
                                                                   int page, int size, String sort) {
        // TODO:: 수집 서비스 원장 재정의 후 목록 구현 (상태·PID 는 노드 에이전트 관측값 병합)
        throw dataSourceNotImplemented();
    }

    /** 탐지된 수집 후보 서비스 목록 — 서비스 추가 위저드 1단계 드롭다운 */
    public List<DiscoveredServiceResponse> getDiscoveredServices(String nodeId) {
        // TODO:: 노드 에이전트의 프로세스/서비스 탐지 결과 조회 (PID·포트·수집 가능 유형 포함)
        throw dataSourceNotImplemented();
    }

    /** 수집 설정 유효성 검증 — 위저드 2단계 '다음' 클릭 시 (로그·트레이스 중 하나 이상, 경로·포트 검증) */
    public CollectServiceValidationResponse validateCollectService(String nodeId, CollectServiceRequest request) {
        // TODO:: 노드 에이전트에 로그 경로 존재·접근 가능 여부, 트레이스 포트 유효성 확인 위임
        throw dataSourceNotImplemented();
    }

    /** 수집 서비스 추가 */
    public CollectServiceResponse createCollectService(String nodeId, CollectServiceRequest request) {
        // TODO:: 수집 서비스 원장 저장 + 노드 에이전트 수집 설정(promtail 등) 반영 경로 확정
        throw dataSourceNotImplemented();
    }

    /** 수집 서비스 편집 */
    public CollectServiceResponse updateCollectService(String nodeId, String serviceId,
                                                       CollectServiceRequest request) {
        // TODO:: 수집 서비스 원장 수정 + 수집 설정 반영
        throw dataSourceNotImplemented();
    }

    /** 수집 서비스 삭제 */
    public void deleteCollectService(String nodeId, String serviceId) {
        // TODO:: 수집 서비스 원장 삭제 + 수집 설정 반영
        throw dataSourceNotImplemented();
    }

    // ── 서비스 로그 (로그 서브탭 + 로그 상세 페이지) ────────────

    /** 로그 파일 목록 — 파일명과 파일별 건수 (상세 페이지 좌측 체크박스) */
    public List<ServiceLogFileResponse> getLogFiles(String nodeId, String serviceId) {
        // TODO:: Loki 레이블(파일) 목록과 건수 조회
        throw dataSourceNotImplemented();
    }

    /** 로그 KPI 카드 4종 — 수집 상태 / 총 로그 수 / Warning / Error */
    public ServiceLogSummaryResponse getLogSummary(String nodeId, String serviceId, String file,
                                                   Instant from, Instant to) {
        // TODO:: Loki count 쿼리로 레벨별 집계
        throw dataSourceNotImplemented();
    }

    /** 로그 발생 추이 — 레벨별 스택 버킷 (분할: 파일별 호출 / 병합: files 복수) */
    public ServiceLogTrendResponse getLogTrend(String nodeId, String serviceId, List<String> files,
                                               Instant from, Instant to, String interval) {
        // TODO:: Loki count_over_time 쿼리로 버킷 구성
        throw dataSourceNotImplemented();
    }

    /** 로그 목록/검색 — 시간 역순. "최근 에러" 는 levels=ERROR 필터로 동일 사용 */
    public ServiceLogListResponse searchLogs(String nodeId, String serviceId, List<String> files,
                                             List<ServiceLogLevel> levels, String keyword,
                                             Instant from, Instant to, int limit) {
        // TODO:: Loki query_range 로 목록 구성 (LokiLogService 확장 검토)
        throw dataSourceNotImplemented();
    }

    private ResponseStatusException dataSourceNotImplemented() {
        return new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "수집 서비스·로그 데이터와 자원 탐색기 로그/트레이스 탭의 매핑 구현 전입니다.");
    }
}
