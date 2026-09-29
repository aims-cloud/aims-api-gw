package com.innogrid.aims.monitoring.controller.resourceexplorer.node;

import com.innogrid.aims.common.log.PortalLog;
import com.innogrid.aims.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeDailyUsageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeDetailMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeVmListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogFileResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogLevel;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogListResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceRequest;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceValidationResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.DiscoveredServiceResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.trace.ServiceTraceSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.trace.ServiceTraceTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.trace.TraceDetailResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.trace.TraceEndpointStatResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.trace.TraceListResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.trace.TraceMethodDistributionResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.node.ResourceExplorerNodeLogService;
import com.innogrid.aims.monitoring.service.resourceexplorer.node.ResourceExplorerNodeTraceService;
import com.innogrid.aims.monitoring.service.resourceexplorer.node.ResourceExplorerNodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > 노드 상세 패널 API.
 *
 * 트리에서 물리 노드를 선택했을 때 오른쪽 패널의 탭별 데이터를 제공한다.
 *   개요 탭        — 주요 지표 카드 6종, 모니터링 요약 차트, 노드 정보 표
 *   가상머신 탭    — 노드에서 실행 중인 VM 목록
 *   자원 사용 현황 탭 — 일자별 일간 최대 사용률·가상머신수 테이블
 *   로그/트레이스 탭 — 수집 서비스 관리(위저드 CRUD·탐지·검증), 서비스별 로그·트레이스 조회
 * 알람 탭은 AlarmHistory/AlarmPolicy API 의 resourceId 필터를 재사용한다.
 * 경로의 nodeId 가 현재 사용자 토큰의 역할 스코프에 없으면 404 — 트리에 노출되지 않는 노드는 조회할 수 없다.
 * 왼쪽 트리는 ResourceExplorerTreeController, MDC 패널은 ResourceExplorerMdcController 가 담당한다.
 */
@Tag(name = "관측 > 자원 탐색기 (노드)", description = "노드 상세 — 주요 지표, 모니터링 요약 차트, 노드 정보, 가상머신 목록, 자원 사용 현황, 수집 서비스·로그")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/node/{nodeId}")
public class ResourceExplorerNodeController {

    private final ResourceExplorerNodeService resourceExplorerNodeService;
    private final ResourceExplorerNodeLogService resourceExplorerNodeLogService;
    private final ResourceExplorerNodeTraceService resourceExplorerNodeTraceService;

    public ResourceExplorerNodeController(ResourceExplorerNodeService resourceExplorerNodeService,
                                          ResourceExplorerNodeLogService resourceExplorerNodeLogService,
                                          ResourceExplorerNodeTraceService resourceExplorerNodeTraceService) {
        this.resourceExplorerNodeService = resourceExplorerNodeService;
        this.resourceExplorerNodeLogService = resourceExplorerNodeLogService;
        this.resourceExplorerNodeTraceService = resourceExplorerNodeTraceService;
    }

    @Operation(summary = "노드 주요 지표",
            description = "개요 탭 카드 6종 — CPU/Memory/File System/GPU/VRAM 사용률(용량 카드는 Free/Total GB 포함), "
                    + "네트워크 수신·송신(bytes/sec, 단위 변환은 프론트), 최근 업데이트 시각을 조회합니다.")
    @GetMapping("/summary")
    public ApiResponse<NodeSummaryResponse> getNodeSummary(@PathVariable String nodeId) {
        return ApiResponse.ok(resourceExplorerNodeService.getNodeSummary(nodeId));
    }

    @Operation(summary = "노드 모니터링 요약 차트",
            description = "metric(CPU|MEMORY|FILE_SYSTEM|NETWORK|GPU|VRAM)별 시계열을 조회합니다. "
                    + "metric 미지정 시 전체 지표(6종)를 배열로 반환합니다. "
                    + "NETWORK 는 receive/transmit 2개 시리즈를 반환합니다. "
                    + "interval(LAST_10M|LAST_1H|LAST_6H|LAST_24H|LAST_30D|LAST_90D)은 조회 기간(지금 기준)이며 "
                    + "버킷 간격(step)은 기간에 맞춰 서버가 정합니다.")
    @GetMapping("/metrics/trend")
    public ApiResponse<List<NodeMetricTrendResponse>> getNodeMetricTrend(
            @PathVariable String nodeId,
            @RequestParam(required = false) NodeDetailMetric metric,
            @RequestParam(defaultValue = "LAST_10M") NodeMetricInterval interval) {
        return ApiResponse.ok(resourceExplorerNodeService.getNodeMetricTrend(nodeId, metric, interval));
    }

    @Operation(summary = "노드 정보",
            description = "개요 탭 노드 정보 표 — 이름/ID/IP/가동일수/유형/가상서버 수/CPU·Memory 할당/스토리지와 "
                    + "우상단 배지용 프로바이더(provider)·플랫폼 유형(platformType)을 조회합니다.")
    @GetMapping("/info")
    public ApiResponse<NodeInfoResponse> getNodeInfo(@PathVariable String nodeId) {
        return ApiResponse.ok(resourceExplorerNodeService.getNodeInfo(nodeId));
    }

    @Operation(summary = "노드 가상머신 목록",
            description = "가상머신 탭 — 이 노드에서 실행 중인 VM 목록. 상태·키워드 필터와 컬럼 정렬, 페이징을 지원합니다. "
                    + "page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping("/vms")
    public ApiResponse<PageResponse<NodeVmListItemResponse>> getNodeVms(
            @PathVariable String nodeId,
            @RequestParam(required = false) NodeStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        return ApiResponse.ok(resourceExplorerNodeService.getNodeVms(nodeId, status, keyword, page, size, sort));
    }

    @Operation(summary = "노드 자원 사용 현황",
            description = "자원 사용 현황 탭 테이블 — 일자별 일간 최대 CPU/Memory/File System 사용률과 일간 최대 가상머신수. "
                    + "값이 null 이면 해당 일자 미수집(\"-\")입니다. "
                    + "interval(LAST_10M|LAST_1H|LAST_6H|LAST_24H|LAST_30D|LAST_90D)은 조회 기간(지금 기준)이며 "
                    + "일 단위 테이블이라 기간에 걸친 일자의 행이 반환됩니다. "
                    + "page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping("/usage")
    public ApiResponse<PageResponse<NodeDailyUsageResponse>> getNodeUsage(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "LAST_30D") NodeMetricInterval interval,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "date,desc") String sort) {
        return ApiResponse.ok(resourceExplorerNodeService.getNodeUsage(nodeId, interval, page, size, sort));
    }

    // ── 로그/트레이스 탭 — 수집 서비스 관리 ─────────────────────

    @Operation(summary = "수집 서비스 목록",
            description = "로그/트레이스 탭 — 서비스명/상태/포트/PID/설명. 키워드 검색과 컬럼 정렬, 페이징을 지원합니다. "
                    + "page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping("/services")
    public ApiResponse<PageResponse<CollectServiceResponse>> getCollectServices(
            @PathVariable String nodeId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "serviceName,asc") String sort) {
        return ApiResponse.ok(resourceExplorerNodeLogService.getCollectServices(
                nodeId, keyword, page, size, sort));
    }

    @Operation(summary = "탐지된 수집 후보 서비스 목록",
            description = "서비스 추가 위저드 1단계 드롭다운. 후보 원천은 둘이며 source 로 구분합니다. "
                    + "LOKI: 이 노드의 수집기가 최근 24시간 동안 Loki 로 보낸 job(수집 설정 묶음), 설명에 수집 파일 경로와 건수. "
                    + "SYSTEMD: node_exporter systemd 콜렉터(--collector.systemd)가 보고하는 활성 *.service 유닛, 로그 경로는 2단계에서 입력. "
                    + "같은 이름은 LOKI 우선, 이름순. PID·포트는 null, capabilities 는 LOG 만입니다. "
                    + "Loki 라벨 매핑이 없으면 LOKI 원천이 빠지고, Loki 조회 실패는 502, systemd 원천은 콜렉터 없음·Thanos 장애 시 조용히 빠집니다.")
    @GetMapping("/services/discovered")
    public ApiResponse<List<DiscoveredServiceResponse>> getDiscoveredServices(@PathVariable String nodeId) {
        return ApiResponse.ok(resourceExplorerNodeLogService.getDiscoveredServices(nodeId));
    }

    @Operation(summary = "수집 설정 유효성 검증",
            description = "위저드 2단계 '다음' 클릭 시 — 이름·설명 길이, 로그 경로 형식(glob 없는 절대 경로)·개수, "
                    + "Syslog 토글 종속, 트레이스 포트 범위를 검증해 issues(field=요청 JSON 경로)로 돌려줍니다. "
                    + "valid=true 이면 observations 에 로그 경로별 최근 24시간 Loki 관측(OBSERVED/NOT_OBSERVED/UNKNOWN)을 "
                    + "함께 줍니다. 미관측은 오류가 아닙니다(아직 수집되지 않는 파일 등록이 목적). 이름 중복은 저장 시 409 입니다.")
    @PostMapping("/services/validate")
    public ApiResponse<CollectServiceValidationResponse> validateCollectService(
            @PathVariable String nodeId,
            @RequestBody CollectServiceRequest request) {
        return ApiResponse.ok(resourceExplorerNodeLogService.validateCollectService(nodeId, request));
    }

    @Operation(summary = "수집 서비스 추가",
            description = "수집 희망 설정을 원장에 저장합니다. 수집기 설정에는 반영되지 않으며 호스트의 파일 존재·권한은 확인하지 않습니다.")
    @PortalLog(screen = "노드 수집 서비스", action = "생성", content = "'수집 서비스 생성: ' + #request.serviceName",
            refType = "COLLECT_SERVICE", refId = "#result.data.serviceId")
    @PostMapping("/services")
    public ApiResponse<CollectServiceResponse> createCollectService(
            @PathVariable String nodeId,
            @RequestBody CollectServiceRequest request) {
        return ApiResponse.ok(resourceExplorerNodeLogService.createCollectService(nodeId, request));
    }

    @Operation(summary = "수집 서비스 편집")
    @PortalLog(screen = "노드 수집 서비스", action = "수정", content = "'수집 서비스 수정: ' + #serviceId",
            refType = "COLLECT_SERVICE", refId = "#serviceId")
    @PutMapping("/services/{serviceId}")
    public ApiResponse<CollectServiceResponse> updateCollectService(
            @PathVariable String nodeId,
            @PathVariable String serviceId,
            @RequestBody CollectServiceRequest request) {
        return ApiResponse.ok(resourceExplorerNodeLogService.updateCollectService(nodeId, serviceId, request));
    }

    @Operation(summary = "수집 서비스 삭제")
    @PortalLog(screen = "노드 수집 서비스", action = "삭제", content = "'수집 서비스 삭제: ' + #serviceId",
            refType = "COLLECT_SERVICE", refId = "#serviceId")
    @DeleteMapping("/services/{serviceId}")
    public ApiResponse<Void> deleteCollectService(
            @PathVariable String nodeId,
            @PathVariable String serviceId) {
        resourceExplorerNodeLogService.deleteCollectService(nodeId, serviceId);
        return ApiResponse.ok(null);
    }

    // ── 로그/트레이스 탭 — 서비스 로그 (로그 서브탭 + 로그 상세 페이지) ──

    @Operation(summary = "서비스 로그 파일 목록",
            description = "로그 상세 페이지 좌측 체크박스용 — 수집 중인 로그 파일명과 파일별 건수를 조회합니다.")
    @GetMapping("/services/{serviceId}/logs/files")
    public ApiResponse<List<ServiceLogFileResponse>> getServiceLogFiles(
            @PathVariable String nodeId,
            @PathVariable String serviceId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.ok(resourceExplorerNodeLogService.getLogFiles(nodeId, serviceId,
                parseLogTime(from, todayStart()), parseLogTime(to, Instant.now())));
    }

    @Operation(summary = "서비스 로그 KPI",
            description = "로그 서브탭 카드 4종 — 수집 상태, 총 로그 수, Warning, Error. "
                    + "from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/services/{serviceId}/logs/summary")
    public ApiResponse<ServiceLogSummaryResponse> getServiceLogSummary(
            @PathVariable String nodeId,
            @PathVariable String serviceId,
            @RequestParam(required = false) String file,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Instant toInstant = parseLogTime(to, Instant.now());
        Instant fromInstant = parseLogTime(from, todayStart());
        return ApiResponse.ok(resourceExplorerNodeLogService.getLogSummary(
                nodeId, serviceId, file, fromInstant, toInstant));
    }

    @Operation(summary = "서비스 로그 발생 추이",
            description = "레벨별(Info/Warning/Debug/Error) 스택 버킷 시계열. "
                    + "분할 모드는 파일별 개별 호출, 병합 모드는 files 복수 지정으로 1회 호출합니다. "
                    + "from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/services/{serviceId}/logs/trend")
    public ApiResponse<ServiceLogTrendResponse> getServiceLogTrend(
            @PathVariable String nodeId,
            @PathVariable String serviceId,
            @RequestParam(required = false) List<String> files,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1h") String interval) {
        Instant toInstant = parseLogTime(to, Instant.now());
        Instant fromInstant = parseLogTime(from, todayStart());
        return ApiResponse.ok(resourceExplorerNodeLogService.getLogTrend(
                nodeId, serviceId, files, fromInstant, toInstant, interval));
    }

    @Operation(summary = "서비스 로그 목록/검색",
            description = "시간 역순 로그 목록 — files·levels 다중 필터, 키워드 검색, limit 지원. "
                    + "'최근 에러' 위젯은 levels=ERROR 와 limit 로 동일 응답을 사용합니다. "
                    + "from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/services/{serviceId}/logs")
    public ApiResponse<ServiceLogListResponse> searchServiceLogs(
            @PathVariable String nodeId,
            @PathVariable String serviceId,
            @RequestParam(required = false) List<String> files,
            @RequestParam(required = false) List<ServiceLogLevel> levels,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit) {
        Instant toInstant = parseLogTime(to, Instant.now());
        Instant fromInstant = parseLogTime(from, todayStart());
        return ApiResponse.ok(resourceExplorerNodeLogService.searchLogs(
                nodeId, serviceId, files, levels, keyword, fromInstant, toInstant, limit));
    }

    // ── 로그/트레이스 탭 — 서비스 트레이스 (트레이스 서브탭 + 트레이스 상세 페이지) ──

    @Operation(summary = "서비스 트레이스 KPI",
            description = "트레이스 서브탭 카드 4종 — 수집 상태, 총 요청 수, 요청률(req/s), 평균 응답(ms). "
                    + "from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/services/{serviceId}/traces/summary")
    public ApiResponse<ServiceTraceSummaryResponse> getServiceTraceSummary(
            @PathVariable String nodeId,
            @PathVariable String serviceId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Instant toInstant = parseLogTime(to, Instant.now());
        Instant fromInstant = parseLogTime(from, todayStart());
        return ApiResponse.ok(resourceExplorerNodeTraceService.getTraceSummary(
                nodeId, serviceId, fromInstant, toInstant));
    }

    @Operation(summary = "서비스 요청량 추이",
            description = "버킷별 요청 수 막대 차트. 차트 드래그 구간 선택의 요청 수 합산은 프론트가 버킷 합으로 계산합니다. "
                    + "from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/services/{serviceId}/traces/trend")
    public ApiResponse<ServiceTraceTrendResponse> getServiceTraceTrend(
            @PathVariable String nodeId,
            @PathVariable String serviceId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1h") String interval) {
        Instant toInstant = parseLogTime(to, Instant.now());
        Instant fromInstant = parseLogTime(from, todayStart());
        return ApiResponse.ok(resourceExplorerNodeTraceService.getTraceTrend(
                nodeId, serviceId, fromInstant, toInstant, interval));
    }

    @Operation(summary = "메서드 분포",
            description = "도넛 차트 — 총 요청 수와 HTTP 메서드(GET/HEAD/POST/PUT/DELETE)별 건수를 조회합니다.")
    @GetMapping("/services/{serviceId}/traces/methods")
    public ApiResponse<TraceMethodDistributionResponse> getServiceTraceMethods(
            @PathVariable String nodeId,
            @PathVariable String serviceId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Instant toInstant = parseLogTime(to, Instant.now());
        Instant fromInstant = parseLogTime(from, todayStart());
        return ApiResponse.ok(resourceExplorerNodeTraceService.getMethodDistribution(
                nodeId, serviceId, fromInstant, toInstant));
    }

    @Operation(summary = "엔드포인트 상위 목록",
            description = "메서드별 상위 엔드포인트와 요청 수를 조회합니다.")
    @GetMapping("/services/{serviceId}/traces/endpoints")
    public ApiResponse<List<TraceEndpointStatResponse>> getServiceTraceEndpoints(
            @PathVariable String nodeId,
            @PathVariable String serviceId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "5") @Min(1) @Max(50) int limit) {
        Instant toInstant = parseLogTime(to, Instant.now());
        Instant fromInstant = parseLogTime(from, todayStart());
        return ApiResponse.ok(resourceExplorerNodeTraceService.getTopEndpoints(
                nodeId, serviceId, fromInstant, toInstant, limit));
    }

    @Operation(summary = "트레이스 포트 목록",
            description = "트레이스 상세 페이지 좌측 체크박스용 — 관측된 포트 목록을 조회합니다.")
    @GetMapping("/services/{serviceId}/traces/ports")
    public ApiResponse<List<Integer>> getServiceTracePorts(
            @PathVariable String nodeId,
            @PathVariable String serviceId) {
        return ApiResponse.ok(resourceExplorerNodeTraceService.getTracePorts(nodeId, serviceId));
    }

    @Operation(summary = "트레이스 목록/검색",
            description = "시간 역순 트레이스 목록 — ports·statusClasses(2XX|4XX|5XX) 다중 필터, "
                    + "엔드포인트 키워드, 최소 소요(ms), limit 지원. 최근 트레이스 위젯과 상세 페이지 목록에 공용입니다. "
                    + "from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/services/{serviceId}/traces")
    public ApiResponse<TraceListResponse> searchServiceTraces(
            @PathVariable String nodeId,
            @PathVariable String serviceId,
            @RequestParam(required = false) List<Integer> ports,
            @RequestParam(required = false) List<String> statusClasses,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer minDurationMs,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit) {
        Instant toInstant = parseLogTime(to, Instant.now());
        Instant fromInstant = parseLogTime(from, todayStart());
        return ApiResponse.ok(resourceExplorerNodeTraceService.searchTraces(
                nodeId, serviceId, ports, statusClasses, keyword, minDurationMs,
                fromInstant, toInstant, limit));
    }

    @Operation(summary = "트레이스 상세",
            description = "트레이스 상세 패널 — 루트/시작/총 소요/스팬 수/상태/포트 요약 카드와 "
                    + "스팬 워터폴(시작 오프셋·소요·전체 대비 %·부모 스팬)을 조회합니다.")
    @GetMapping("/services/{serviceId}/traces/{traceId}")
    public ApiResponse<TraceDetailResponse> getServiceTraceDetail(
            @PathVariable String nodeId,
            @PathVariable String serviceId,
            @PathVariable String traceId) {
        return ApiResponse.ok(resourceExplorerNodeTraceService.getTraceDetail(nodeId, serviceId, traceId));
    }

    @Operation(summary = "트레이스 관련 로그",
            description = "트레이스 상세 패널 '관련 로그' 토글 — 트레이스 시간창의 로그를 조회합니다. "
                    + "분할/병합 모드는 files 조합으로 처리하며 응답은 서비스 로그 목록과 동일 구조입니다.")
    @GetMapping("/services/{serviceId}/traces/{traceId}/logs")
    public ApiResponse<ServiceLogListResponse> getServiceTraceLogs(
            @PathVariable String nodeId,
            @PathVariable String serviceId,
            @PathVariable String traceId,
            @RequestParam(required = false) List<String> files) {
        return ApiResponse.ok(resourceExplorerNodeTraceService.getTraceLogs(
                nodeId, serviceId, traceId, files));
    }

    /** from 기본값: 오늘 00:00:00 (서버 타임존) */
    private static Instant todayStart() {
        return LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private static Instant parseLogTime(String value, Instant fallback) {
        if (value == null) return fallback;
        try { return Instant.parse(value); }
        catch (java.time.format.DateTimeParseException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "from/to는 ISO-8601 시각이어야 합니다.");
        }
    }
}

