package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.util.*;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.*;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.*;
import com.innogrid.aims.monitoring.entity.CollectService;
import com.innogrid.aims.monitoring.log.PortalLogContextResolver;
import com.innogrid.aims.monitoring.repository.CollectServiceRepository;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 원장 CRUD와 역할 스코프 검증. 수집기에 설정을 적용하지 않는다. */
@Service
@Transactional(readOnly = true)
public class ResourceExplorerNodeLogService {

    /** 목록 정렬에 허용하는 필드 */
    private static final Set<String> SORT_FIELDS = Set.of("serviceName", "description", "logStatus", "serviceId");
    private static final Set<String> SORT_DIRECTIONS = Set.of("asc", "desc");
    private static final String UNIQUE_NAME_CONSTRAINT = "uk_collect_services_asset_name";
    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_DESCRIPTION_LENGTH = 500;
    private static final int MAX_PATH_LENGTH = 500;
    private static final int MAX_CHILD_ROWS = 100;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_KEYWORD_LENGTH = 1000;
    private static final int MAX_SEARCH_LIMIT = 1000;

    private final NodeAccessService access;
    private final CollectServiceRepository repository;
    private final NodeLogQueries queries;
    private final PortalLogContextResolver users;

    public ResourceExplorerNodeLogService(NodeAccessService access,
                                          CollectServiceRepository repository,
                                          NodeLogQueries queries,
                                          PortalLogContextResolver users) {
        this.access = access;
        this.repository = repository;
        this.queries = queries;
        this.users = users;
    }

    // ── 수집 서비스 관리 ────────────────────────────────────────

    public PageResponse<CollectServiceResponse> getCollectServices(String nodeId, String keyword,
                                                                   int page, int size, String sort) {
        NodeAccessService.AccessibleNode node = access.require(nodeId);
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw bad("page/size 범위가 잘못되었습니다.");
        }
        String[] order = parseSort(sort);

        List<CollectService> services = findByKeyword(node.host().assetId(), keyword);
        List<String> paths = services.stream()
                .flatMap(service -> service.getLogPaths().stream())
                .distinct()
                .toList();

        // 노드당 1회만 관측하고 서비스별로 나눠 담는다.
        NodeLogQueries.Observation observed = queries.recent(node.host(), paths);
        List<CollectServiceResponse> rows = services.stream()
                .map(service -> response(service, observed.status(service.getLogPaths())))
                .sorted(comparatorOf(order))
                .toList();

        return pageOf(rows, page, size);
    }

    public List<DiscoveredServiceResponse> getDiscoveredServices(String nodeId) {
        access.require(nodeId);
        throw new BusinessException(HttpStatus.NOT_IMPLEMENTED, "호스트 서비스 탐지 수단이 없습니다.");
    }

    public CollectServiceValidationResponse validateCollectService(String nodeId, CollectServiceRequest request) {
        access.require(nodeId);
        throw new BusinessException(HttpStatus.NOT_IMPLEMENTED, "호스트 경로 존재·접근 권한 검증 수단이 없습니다.");
    }

    @Transactional
    public CollectServiceResponse createCollectService(String nodeId, CollectServiceRequest request) {
        NodeAccessService.AccessibleNode node = access.require(nodeId);
        validate(request);

        String user = users.resolve().userId();
        if (user == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "사용자를 확인할 수 없습니다.");
        }

        CollectService service = new CollectService(node.asset(), user);
        save(service, node.host().assetId(), request);

        // 쓰기는 Loki 가용성에 의존하지 않는다. 관측은 목록 재조회에서 수행한다.
        return response(service, initialStatus(node.host(), service));
    }

    @Transactional
    public CollectServiceResponse updateCollectService(String nodeId, String serviceId,
                                                        CollectServiceRequest request) {
        NodeAccessService.AccessibleNode node = access.require(nodeId);
        CollectService service = requireService(node.host().assetId(), serviceId);
        validate(request);
        save(service, node.host().assetId(), request);

        return response(service, initialStatus(node.host(), service));
    }

    @Transactional
    public void deleteCollectService(String nodeId, String serviceId) {
        NodeAccessService.AccessibleNode node = access.require(nodeId);
        repository.delete(requireService(node.host().assetId(), serviceId));
    }

    // ── 서비스 로그 조회 ────────────────────────────────────────

    public List<ServiceLogFileResponse> getLogFiles(String nodeId, String serviceId,
                                                     Instant from, Instant to) {
        Context context = context(nodeId, serviceId);
        NodeLogQueries.Window window = NodeLogQueries.window(from, to);

        List<String> paths = context.service().getLogPaths().stream().sorted().toList();
        Map<String, Long> counts = queries.fileCounts(context.host(), paths, window);

        // 기간 내 로그가 없는 등록 파일도 0건으로 남긴다.
        return paths.stream()
                .map(path -> new ServiceLogFileResponse(baseName(path), path, counts.getOrDefault(path, 0L)))
                .toList();
    }

    public ServiceLogSummaryResponse getLogSummary(String nodeId, String serviceId, String file,
                                                    Instant from, Instant to) {
        Context context = context(nodeId, serviceId);
        NodeLogQueries.Window window = NodeLogQueries.window(from, to);

        List<String> paths = selectPaths(context.service(), file == null ? null : List.of(file));
        Map<ServiceLogLevel, Long> counts = queries.levelCounts(context.host(), paths, window);

        // 건수는 file 필터를 따르지만 수집 상태는 항상 서비스 전체 경로로 판정한다.
        Set<String> allPaths = context.service().getLogPaths();
        LogStatus status = queries.recent(context.host(), allPaths).status(allPaths);

        return new ServiceLogSummaryResponse(
                status,
                counts.values().stream().mapToLong(Long::longValue).sum(),
                counts.getOrDefault(ServiceLogLevel.WARNING, 0L),
                counts.getOrDefault(ServiceLogLevel.ERROR, 0L),
                counts.getOrDefault(ServiceLogLevel.UNKNOWN, 0L));
    }

    public ServiceLogTrendResponse getLogTrend(String nodeId, String serviceId, List<String> files,
                                                Instant from, Instant to, String interval) {
        Context context = context(nodeId, serviceId);

        return queries.trend(
                context.host(),
                selectPaths(context.service(), files),
                NodeLogQueries.window(from, to),
                interval);
    }

    public ServiceLogListResponse searchLogs(String nodeId, String serviceId, List<String> files,
                                              List<ServiceLogLevel> levels, String keyword,
                                              Instant from, Instant to, int limit) {
        Context context = context(nodeId, serviceId);
        if (limit < 1 || limit > MAX_SEARCH_LIMIT) {
            throw bad("limit은 1~" + MAX_SEARCH_LIMIT + "입니다.");
        }
        if (keyword != null && keyword.length() > MAX_KEYWORD_LENGTH) {
            throw bad("검색어는 " + MAX_KEYWORD_LENGTH + "자 이내입니다.");
        }

        return queries.search(
                context.host(),
                selectPaths(context.service(), files),
                levels,
                keyword,
                NodeLogQueries.window(from, to),
                limit);
    }

    /** 미구현 트레이스도 동일한 노드 스코프·서비스 소속 검증을 거친다. */
    public void requireAccessibleService(String nodeId, String serviceId) {
        context(nodeId, serviceId);
    }

    // ── 조회 대상 확인 ──────────────────────────────────────────

    /** 스코프를 통과한 노드와 그 노드에 속한 수집 서비스 */
    private record Context(ScopedHost host, CollectService service) {}

    private Context context(String nodeId, String serviceId) {
        NodeAccessService.AccessibleNode node = access.require(nodeId);
        return new Context(node.host(), requireService(node.host().assetId(), serviceId));
    }

    private CollectService requireService(long assetId, String serviceId) {
        long id;
        try {
            id = Long.parseLong(serviceId);
        } catch (NumberFormatException e) {
            throw notFound();
        }
        return repository.findByIdAndAsset_Id(id, assetId)
                .orElseThrow(ResourceExplorerNodeLogService::notFound);
    }

    /** 요청한 파일이 없으면 등록 경로 전체, 있으면 등록 경로 안의 것만 허용한다. */
    static List<String> selectPaths(CollectService service, List<String> requested) {
        if (requested == null) {
            return service.getLogPaths().stream().sorted().toList();
        }
        if (requested.isEmpty() || !service.getLogPaths().containsAll(requested)) {
            throw bad("해당 서비스에 등록된 전체 파일 경로만 조회할 수 있습니다.");
        }
        return requested.stream().distinct().sorted().toList();
    }

    // ── 목록 조립 ──────────────────────────────────────────────

    private List<CollectService> findByKeyword(long assetId, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return repository.findByAsset_Id(assetId);
        }
        String needle = keyword.toLowerCase(Locale.ROOT);
        return repository.findByAsset_Id(assetId).stream()
                .filter(service -> service.getServiceName().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    /** sort 는 "{필드},{asc|desc}" 형식이다. */
    private static String[] parseSort(String sort) {
        String[] order = sort == null ? new String[0] : sort.split(",", -1);
        if (order.length != 2 || !SORT_FIELDS.contains(order[0]) || !SORT_DIRECTIONS.contains(order[1])) {
            throw bad("지원하지 않는 정렬입니다.");
        }
        return order;
    }

    private static Comparator<CollectServiceResponse> comparatorOf(String[] order) {
        Comparator<CollectServiceResponse> comparator = switch (order[0]) {
            case "description" -> Comparator.comparing(CollectServiceResponse::description,
                    Comparator.nullsLast(String::compareTo));
            case "logStatus" -> Comparator.comparing(row -> row.logStatus().name());
            case "serviceId" -> Comparator.comparingLong(row -> Long.parseLong(row.serviceId()));
            default -> Comparator.comparing(CollectServiceResponse::serviceName);
        };
        if (order[1].equals("desc")) {
            comparator = comparator.reversed();
        }
        // 동점은 serviceId 오름차순으로 고정해 페이지 간 순서를 유지한다.
        return comparator.thenComparing(CollectServiceResponse::serviceId);
    }

    private static PageResponse<CollectServiceResponse> pageOf(List<CollectServiceResponse> rows,
                                                                int page, int size) {
        int total = rows.size();
        int start = (int) Math.min((long) (page - 1) * size, total);
        int end = Math.min(start + size, total);
        int totalPages = (int) Math.ceil((double) total / size);

        return new PageResponse<>(rows.subList(start, end), page, size, total, totalPages);
    }

    // ── 저장과 검증 ────────────────────────────────────────────

    private void save(CollectService service, long assetId, CollectServiceRequest request) {
        long selfId = service.getId() == null ? -1L : service.getId();
        if (repository.existsByAsset_IdAndServiceNameAndIdNot(assetId, request.serviceName().trim(), selfId)) {
            throw conflict();
        }

        service.update(request);
        try {
            repository.saveAndFlush(service);
        } catch (DataIntegrityViolationException e) {
            // 동시 생성 경합은 검사 이후에도 유일 제약에서 걸린다.
            if (isDuplicateName(e)) {
                throw conflict();
            }
            throw e;
        }
    }

    private static boolean isDuplicateName(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && violation.getConstraintName() != null
                    && violation.getConstraintName().toLowerCase(Locale.ROOT).contains(UNIQUE_NAME_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }

    static void validate(CollectServiceRequest request) {
        if (request == null || request.serviceName() == null || request.serviceName().isBlank()
                || request.serviceName().trim().length() > MAX_NAME_LENGTH) {
            throw bad("서비스 이름은 공백이 아닌 " + MAX_NAME_LENGTH + "자 이내입니다.");
        }
        if (request.description() != null && request.description().length() > MAX_DESCRIPTION_LENGTH) {
            throw bad("설명은 " + MAX_DESCRIPTION_LENGTH + "자 이내입니다.");
        }

        validateLogCollection(request.logCollection());
        validateTraceCollection(request.traceCollection());
        // 전체 비활성화도 저장 가능. 활성화 여부와 과거 로그 조회 권한은 독립적이다.
    }

    private static void validateLogCollection(CollectServiceRequest.LogCollection logs) {
        if (logs == null) {
            return;
        }
        if (logs.syslogEnabled() && !logs.enabled()) {
            throw bad("Syslog 설정은 로그 활성화가 필요합니다.");
        }
        if (logs.enabled() && logs.logPaths().isEmpty()) {
            throw bad("로그 활성화 시 경로가 필요합니다.");
        }
        if (logs.logPaths().size() > MAX_CHILD_ROWS) {
            throw bad("로그 경로는 최대 " + MAX_CHILD_ROWS + "개입니다.");
        }
        for (String path : logs.logPaths()) {
            if (!isAbsoluteFilePath(path)) {
                throw bad("로그 경로는 glob 없는 절대 파일 경로(" + MAX_PATH_LENGTH + "자 이내)여야 합니다.");
            }
        }
    }

    private static boolean isAbsoluteFilePath(String path) {
        return path != null
                && path.startsWith("/")
                && !path.endsWith("/")
                && path.length() <= MAX_PATH_LENGTH
                && path.chars().noneMatch(Character::isISOControl)
                && !path.matches(".*[\\*?\\[\\]{}].*");
    }

    private static void validateTraceCollection(CollectServiceRequest.TraceCollection traces) {
        if (traces == null) {
            return;
        }
        if (traces.ports().size() > MAX_CHILD_ROWS) {
            throw bad("트레이스 포트는 최대 " + MAX_CHILD_ROWS + "개입니다.");
        }
        if (traces.ports().stream().anyMatch(port -> port == null || port < 1 || port > 65535)) {
            throw bad("포트는 1~65535입니다.");
        }
    }

    // ── 응답 변환 ──────────────────────────────────────────────

    /** status·port·pid 는 관측 수단이 없어 null, capabilities 는 미확정이라 빈 목록이다. */
    private static CollectServiceResponse response(CollectService service, LogStatus status) {
        return new CollectServiceResponse(
                String.valueOf(service.getId()),
                service.getServiceName(),
                null,
                status,
                null,
                null,
                service.getDescription(),
                List.of(),
                new CollectServiceRequest.LogCollection(
                        service.isLogEnabled(),
                        service.isSyslogEnabled(),
                        service.getLogPaths().stream().sorted().toList()),
                new CollectServiceRequest.TraceCollection(
                        service.isTraceEnabled(),
                        service.getTracePorts().stream().sorted().toList()));
    }

    /** 쓰기 응답은 Loki 를 조회하지 않으므로 미관측(UNKNOWN) 또는 미설정만 나온다. */
    private static LogStatus initialStatus(ScopedHost host, CollectService service) {
        boolean unconfigured = service.getLogPaths().isEmpty() || !NodeLogQueries.configured(host);
        return unconfigured ? LogStatus.UNCONFIGURED : LogStatus.UNKNOWN;
    }

    private static String baseName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    // ── 예외 ───────────────────────────────────────────────────

    private static BusinessException conflict() {
        return new BusinessException(HttpStatus.CONFLICT, "같은 이름의 서비스가 이미 있습니다.");
    }

    private static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "수집 서비스를 찾을 수 없습니다.");
    }

    private static BusinessException bad(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }
}
