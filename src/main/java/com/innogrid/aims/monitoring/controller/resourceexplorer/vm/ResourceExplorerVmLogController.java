package com.innogrid.aims.monitoring.controller.resourceexplorer.vm;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.common.log.PortalLog;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogFileResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogLevel;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogListResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.ServiceLogTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.AgentTargetsStatusResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceRequest;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceValidationResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.DiscoveredServiceResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.VmAgentInstallResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.vm.ResourceExplorerVmLogService;
import com.innogrid.aims.monitoring.service.resourceexplorer.vm.VmAgentInstallService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
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
 * 자원 탐색기 > VM 로그 탭 API — 수집 서비스 관리·후보·검증·에이전트 반영 상태·서비스 로그 조회.
 *
 * 물리 노드 로그 탭(ResourceExplorerNodeController 의 /services…)과 요청·응답이 같고, VM 식별만 다른 VM 패널처럼
 * nsId·infraId·vmId 쿼리 파라미터로 한다(트리 VIRTUAL_MACHINE 노드의 같은 이름 필드). 스코프 밖·없는 VM 은 404 다.
 * 로그는 VM 의 Alloy 가 tb_ns·tb_infra·tb_node 라벨로 보낸 Loki 스트림에서 읽는다. 트레이스는 범위 제외라 두지 않는다.
 */
@Tag(name = "관측 > 자원 탐색기 (VM 로그)", description = "VM 로그 탭 — 수집 서비스 관리, 후보·검증, 에이전트 반영 상태, 서비스 로그 조회")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/vm/services")
public class ResourceExplorerVmLogController {

    private final ResourceExplorerVmLogService vmLogs;
    private final VmAgentInstallService agentInstall;

    public ResourceExplorerVmLogController(ResourceExplorerVmLogService vmLogs, VmAgentInstallService agentInstall) {
        this.vmLogs = vmLogs;
        this.agentInstall = agentInstall;
    }

    // ── 수집 서비스 관리 ─────────────────────────────────────────

    @Operation(summary = "VM 수집 서비스 목록",
            description = "VM 로그 탭 — 서비스명/로그 상태/설명. 키워드 검색과 컬럼 정렬, 페이징을 지원합니다. "
                    + "page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다. 응답은 노드 수집 서비스 목록과 같습니다.")
    @GetMapping
    public ApiResponse<PageResponse<CollectServiceResponse>> getCollectServices(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "serviceName,asc") String sort) {
        return ApiResponse.ok(vmLogs.getCollectServices(roleName, nsId, infraId, vmId, keyword, page, size, sort));
    }

    @Operation(summary = "VM 수집 후보 서비스 목록",
            description = "서비스 추가 위저드 1단계. LOKI: 이 VM 의 수집기가 최근 24시간 Loki 로 보낸 job·파일, "
                    + "SYSTEMD: VM node_exporter systemd 콜렉터의 활성 *.service 유닛(로그 경로 프리셋 포함). 규칙은 노드 후보와 같습니다.")
    @GetMapping("/discovered")
    public ApiResponse<List<DiscoveredServiceResponse>> getDiscoveredServices(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId) {
        return ApiResponse.ok(vmLogs.getDiscoveredServices(roleName, nsId, infraId, vmId));
    }

    @Operation(summary = "VM 수집 설정 유효성 검증",
            description = "위저드 2단계 — 입력 규칙 위반은 issues, valid=true 면 로그 경로별 최근 24시간 Loki 관측을 observations 로 줍니다.")
    @PostMapping("/validate")
    public ApiResponse<CollectServiceValidationResponse> validateCollectService(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId,
            @RequestBody CollectServiceRequest request) {
        return ApiResponse.ok(vmLogs.validateCollectService(roleName, nsId, infraId, vmId, request));
    }

    @Operation(summary = "VM 수집 에이전트 목록 반영 상태",
            description = "이 VM 의 Alloy 가 원장의 수집 파일 목록을 가져갔는지. NO_AGENT: 에이전트 미설치(에이전트 행 없음), "
                    + "PENDING: 아직 가져가지 않음(message 안내), APPLIED: 반영됨(appliedAt), FAILED: 실패 사유(message). "
                    + "VM 은 항상 PULL 전송(Alloy 가 30초마다 tb 키로 목록 API 를 가져감)입니다.")
    @GetMapping("/agent")
    public ApiResponse<AgentTargetsStatusResponse> getAgentTargetsStatus(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId) {
        return ApiResponse.ok(vmLogs.getAgentTargetsStatus(roleName, nsId, infraId, vmId));
    }

    @Operation(summary = "VM 수집 에이전트 설치",
            description = "Tumblebug 원격 명령으로 이 VM 한 대에 node_exporter(:9100)와 Grafana Alloy(systemd aims-alloy)를 설치합니다. "
                    + "수 분 걸려 바로 INSTALLING 을 돌려주고 백그라운드에서 진행하며, 결과는 GET /agent/install 로 봅니다. "
                    + "다시 부르면 재설치(같은 결과)입니다. 404: 스코프 밖·없는 VM, 409: Running 이 아니거나 설치 진행 중, "
                    + "503: 서버의 VM 설치 설정(Loki·목록 API 주소) 미구성. VM 은 github.com(내려받기), Loki 3100, 백엔드 8080 으로 나갈 수 있어야 합니다.")
    @PortalLog(screen = "VM 수집 서비스", action = "에이전트 설치",
            content = "'VM 수집 에이전트 설치: ' + #nsId + '/' + #infraId + '/' + #vmId")
    @PostMapping("/agent/install")
    public ApiResponse<VmAgentInstallResponse> installAgent(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId) {
        return ApiResponse.ok(agentInstall.install(roleName, nsId, infraId, vmId));
    }

    @Operation(summary = "VM 수집 에이전트 설치 상태",
            description = "NOT_INSTALLED(에이전트 행 없음), INSTALLING(installStep 진행 중), INSTALLED(installedAt·agentVersion), "
                    + "FAILED(installStep 실패 단계, installError 사유). 화면은 진행 중이면 몇 초 간격으로 다시 부릅니다.")
    @GetMapping("/agent/install")
    public ApiResponse<VmAgentInstallResponse> getAgentInstallStatus(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId) {
        return ApiResponse.ok(agentInstall.status(roleName, nsId, infraId, vmId));
    }

    @Operation(summary = "VM 수집 서비스 추가",
            description = "수집 희망 설정을 원장에 저장하고, 커밋 뒤 이 VM 에이전트의 목록에 반영합니다(Alloy 가 다음 폴링에 가져감).")
    @PortalLog(screen = "VM 수집 서비스", action = "생성", content = "'VM 수집 서비스 생성: ' + #vmId + ' / ' + #request.serviceName",
            refType = "COLLECT_SERVICE", refId = "#result.data.serviceId")
    @PostMapping
    public ApiResponse<CollectServiceResponse> createCollectService(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId,
            @RequestBody CollectServiceRequest request) {
        return ApiResponse.ok(vmLogs.createCollectService(roleName, nsId, infraId, vmId, request));
    }

    @Operation(summary = "VM 수집 서비스 편집")
    @PortalLog(screen = "VM 수집 서비스", action = "수정", content = "'VM 수집 서비스 수정: ' + #serviceId",
            refType = "COLLECT_SERVICE", refId = "#serviceId")
    @PutMapping("/{serviceId}")
    public ApiResponse<CollectServiceResponse> updateCollectService(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId,
            @PathVariable String serviceId,
            @RequestBody CollectServiceRequest request) {
        return ApiResponse.ok(vmLogs.updateCollectService(roleName, nsId, infraId, vmId, serviceId, request));
    }

    @Operation(summary = "VM 수집 서비스 삭제")
    @PortalLog(screen = "VM 수집 서비스", action = "삭제", content = "'VM 수집 서비스 삭제: ' + #serviceId",
            refType = "COLLECT_SERVICE", refId = "#serviceId")
    @DeleteMapping("/{serviceId}")
    public ApiResponse<Void> deleteCollectService(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId,
            @PathVariable String serviceId) {
        vmLogs.deleteCollectService(roleName, nsId, infraId, vmId, serviceId);
        return ApiResponse.ok(null);
    }

    // ── 서비스 로그 ──────────────────────────────────────────────

    @Operation(summary = "VM 서비스 로그 파일 목록", description = "등록 파일과 파일별 건수. from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/{serviceId}/logs/files")
    public ApiResponse<List<ServiceLogFileResponse>> getServiceLogFiles(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId,
            @PathVariable String serviceId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.ok(vmLogs.getLogFiles(roleName, nsId, infraId, vmId, serviceId,
                parseLogTime(from, todayStart()), parseLogTime(to, Instant.now())));
    }

    @Operation(summary = "VM 서비스 로그 KPI", description = "수집 상태, 총 로그 수, Warning, Error. from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/{serviceId}/logs/summary")
    public ApiResponse<ServiceLogSummaryResponse> getServiceLogSummary(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId,
            @PathVariable String serviceId,
            @RequestParam(required = false) String file,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.ok(vmLogs.getLogSummary(roleName, nsId, infraId, vmId, serviceId, file,
                parseLogTime(from, todayStart()), parseLogTime(to, Instant.now())));
    }

    @Operation(summary = "VM 서비스 로그 발생 추이", description = "레벨별 스택 버킷 시계열. from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/{serviceId}/logs/trend")
    public ApiResponse<ServiceLogTrendResponse> getServiceLogTrend(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId,
            @PathVariable String serviceId,
            @RequestParam(required = false) List<String> files,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1h") String interval) {
        return ApiResponse.ok(vmLogs.getLogTrend(roleName, nsId, infraId, vmId, serviceId, files,
                parseLogTime(from, todayStart()), parseLogTime(to, Instant.now()), interval));
    }

    @Operation(summary = "VM 서비스 로그 목록/검색",
            description = "시간 역순 로그 목록 — files·levels 다중 필터, 키워드 검색, limit 지원. from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/{serviceId}/logs")
    public ApiResponse<ServiceLogListResponse> searchServiceLogs(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId,
            @PathVariable String serviceId,
            @RequestParam(required = false) List<String> files,
            @RequestParam(required = false) List<ServiceLogLevel> levels,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit) {
        return ApiResponse.ok(vmLogs.searchLogs(roleName, nsId, infraId, vmId, serviceId, files, levels, keyword,
                parseLogTime(from, todayStart()), parseLogTime(to, Instant.now()), limit));
    }

    private static Instant todayStart() {
        return LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private static Instant parseLogTime(String value, Instant fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "from/to는 ISO-8601 시각이어야 합니다.");
        }
    }
}
