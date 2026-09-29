package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.log.*;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.*;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceValidationResponse.Issue;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceValidationResponse.ObservationStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceValidationResponse.PathObservation;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.DiscoveredServiceResponse.DiscoveredSource;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceUnitState;
import com.innogrid.aims.monitoring.ResourceExplorerProperties;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostServiceStatusMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostServiceStatusMetricsQueryService.Unit;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import com.innogrid.aims.monitoring.service.agent.AgentTargetsService;
import com.innogrid.aims.monitoring.service.agent.CollectServicesChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
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
    /** 후보 탐지·검증 관측 기간. 수집기가 이 기간에 실제로 보낸 파일만 후보로 낸다. */
    private static final long OBSERVATION_LOOKBACK_SECONDS = 86_400L;

    private static final String SERVICE_UNIT_SUFFIX = ".service";

    private final NodeAccessService access;
    private final CollectServiceRepository repository;
    private final NodeLogQueries queries;
    private final PortalLogContextResolver users;
    private final HostServiceStatusMetricsQueryService systemdUnits;
    private final Pattern systemdExclude;
    private final ApplicationEventPublisher events;
    private final AgentTargetsService agentTargets;

    public ResourceExplorerNodeLogService(NodeAccessService access,
                                          CollectServiceRepository repository,
                                          NodeLogQueries queries,
                                          PortalLogContextResolver users,
                                          HostServiceStatusMetricsQueryService systemdUnits,
                                          ResourceExplorerProperties properties,
                                          ApplicationEventPublisher events,
                                          AgentTargetsService agentTargets) {
        this.access = access;
        this.repository = repository;
        this.queries = queries;
        this.users = users;
        this.systemdUnits = systemdUnits;
        this.events = events;
        this.agentTargets = agentTargets;
        String exclude = properties.getDiscoverySystemdExclude();
        this.systemdExclude = exclude == null || exclude.isBlank() ? null : Pattern.compile(exclude);
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

    /**
     * 후보 두 원천을 이름순 한 목록으로 합친다.
     * <ul>
     *   <li>LOKI — 이 노드의 수집기가 최근 24시간 동안 Loki 로 보낸 job(수집 설정 묶음)과 그 파일. 라벨 매핑이 없는 노드는
     *       이 원천이 없고, Loki 조회 실패는 502 로 올린다(후보 없음과 구분).</li>
     *   <li>SYSTEMD — node_exporter systemd 콜렉터가 보고하는 활성 상태 {@code *.service} 유닛. 로그 경로는 모르므로 2단계 입력이
     *       필요하다. exporter 타겟이 없거나 콜렉터가 꺼져 있거나 Thanos 장애면 이 원천만 조용히 빠진다(보조 원천).</li>
     * </ul>
     * 같은 이름이면 Loki 후보를 우선한다(이미 수집 중인 파일 정보가 더 유용). 호스트 프로세스를 보는 수단은 없으므로 PID·포트는 null,
     * capabilities 는 LOG 뿐이다. 이미 등록된 이름도 후보에 남긴다 — 편집 위저드가 같은 목록으로 현재 서비스를 표시하고, 중복은
     * 저장 시 409 로 막힌다.
     */
    public List<DiscoveredServiceResponse> getDiscoveredServices(String nodeId) {
        NodeAccessService.AccessibleNode node = access.require(nodeId);
        Map<String, DiscoveredServiceResponse> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        if (NodeLogQueries.configured(node.host())) {
            Map<String, List<NodeLogQueries.DiscoveredFile>> byJob = new TreeMap<>();
            for (NodeLogQueries.DiscoveredFile file : queries.recentFiles(node.host(), OBSERVATION_LOOKBACK_SECONDS)) {
                String name = file.job() == null || file.job().isBlank() ? baseName(file.path()) : file.job();
                byJob.computeIfAbsent(name, ignored -> new ArrayList<>()).add(file);
            }
            for (Map.Entry<String, List<NodeLogQueries.DiscoveredFile>> entry : byJob.entrySet()) {
                List<String> observedPaths = entry.getValue().stream()
                        .map(NodeLogQueries.DiscoveredFile::path).distinct().sorted().toList();
                byName.put(entry.getKey(), new DiscoveredServiceResponse(entry.getKey(), discoveredDescription(entry.getValue()),
                        null, null, List.of(CollectCapability.LOG), DiscoveredSource.LOKI, observedPaths));
            }
        }

        String instance = node.host().instance();
        if (instance != null && !instance.isBlank()) {
            for (Unit unit : systemdUnits.units(MetricSelector.instance(instance))) {
                String serviceName = candidateNameOf(unit);
                if (serviceName == null || byName.containsKey(serviceName)) {
                    continue;
                }
                String type = unit.type() == null || unit.type().isBlank() ? "" : ", " + unit.type();
                List<String> presets = LogPathPresets.forUnit(unit.name());
                String hint = presets.isEmpty() ? " — 로그 경로는 2단계에서 입력" : " — 기본 로그 경로 제안 " + presets.size() + "건";
                byName.put(serviceName, new DiscoveredServiceResponse(serviceName,
                        "systemd 유닛 " + unit.name() + " (active" + type + ")" + hint,
                        null, null, List.of(CollectCapability.LOG), DiscoveredSource.SYSTEMD, presets));
            }
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * 활성 상태의 {@code *.service} 유닛만, 제외 패턴을 거쳐 ".service" 를 뗀 이름으로. 후보가 아니면 null.
     * {@code oneshot} 은 한 번 실행되고 끝나는 유닛(cloud-init, apparmor 등)이라 상주 프로세스가 없어 제외한다.
     */
    private String candidateNameOf(Unit unit) {
        if (unit.state() != ServiceUnitState.ACTIVE || unit.name() == null || !unit.name().endsWith(SERVICE_UNIT_SUFFIX)
                || "oneshot".equals(unit.type())) {
            return null;
        }
        if (systemdExclude != null && systemdExclude.matcher(unit.name()).find()) {
            return null;
        }
        String name = unit.name().substring(0, unit.name().length() - SERVICE_UNIT_SUFFIX.length()).trim();
        return name.isEmpty() ? null : name;
    }

    /** 설명에 파일 경로를 실어 2단계 경로 입력의 힌트로 쓴다(응답 계약에 경로 필드가 없다). */
    private static String discoveredDescription(List<NodeLogQueries.DiscoveredFile> files) {
        String joined = files.stream()
                .sorted(Comparator.comparing(NodeLogQueries.DiscoveredFile::path))
                .map(file -> file.path() + " (" + file.count() + "건)")
                .collect(Collectors.joining(", "));
        String text = "최근 24시간 수집 파일: " + joined;
        return text.length() <= MAX_DESCRIPTION_LENGTH ? text : text.substring(0, MAX_DESCRIPTION_LENGTH - 1) + "…";
    }

    /**
     * 위저드 2단계 검증. 입력 규칙 위반만 valid=false 이고, 로그 경로별 최근 24시간 Loki 관측은 참고 정보로 함께 준다.
     * 이름 중복은 생성·편집 요청을 구분할 수 없어 여기서 검사하지 않는다(저장 시 409).
     */
    public CollectServiceValidationResponse validateCollectService(String nodeId, CollectServiceRequest request) {
        NodeAccessService.AccessibleNode node = access.require(nodeId);
        List<Issue> issues = issues(request);
        if (!issues.isEmpty()) {
            return new CollectServiceValidationResponse(false, issues, List.of());
        }
        return new CollectServiceValidationResponse(true, List.of(), observe(node.host(), request));
    }

    private List<PathObservation> observe(ScopedHost host, CollectServiceRequest request) {
        List<String> paths = request.logCollection() == null ? List.of()
                : request.logCollection().logPaths().stream().distinct().sorted().toList();
        if (paths.isEmpty()) {
            return List.of();
        }
        Map<String, Long> counts = null;
        if (NodeLogQueries.configured(host)) {
            try {
                Instant now = Instant.now();
                counts = queries.fileCounts(host, paths,
                        NodeLogQueries.window(now.minusSeconds(OBSERVATION_LOOKBACK_SECONDS), now));
            } catch (BusinessException e) {
                // Loki 장애는 관측 불가(UNKNOWN)로 남기고 검증 자체는 통과시킨다.
                if (e.getStatus() != HttpStatus.BAD_GATEWAY) {
                    throw e;
                }
            }
        }
        List<PathObservation> rows = new ArrayList<>();
        for (String path : paths) {
            ObservationStatus status = counts == null ? ObservationStatus.UNKNOWN
                    : counts.getOrDefault(path, 0L) > 0 ? ObservationStatus.OBSERVED : ObservationStatus.NOT_OBSERVED;
            rows.add(new PathObservation(path, status));
        }
        return rows;
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
        events.publishEvent(new CollectServicesChangedEvent(node.host().assetId()));   // 커밋 뒤 노드 목록 반영

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
        events.publishEvent(new CollectServicesChangedEvent(node.host().assetId()));

        return response(service, initialStatus(node.host(), service));
    }

    @Transactional
    public void deleteCollectService(String nodeId, String serviceId) {
        NodeAccessService.AccessibleNode node = access.require(nodeId);
        repository.delete(requireService(node.host().assetId(), serviceId));
        events.publishEvent(new CollectServicesChangedEvent(node.host().assetId()));
    }

    /** 이 노드의 수집 에이전트에 원장 목록이 반영됐는지 — 스코프 검증 뒤 에이전트 원장 상태 */
    public AgentTargetsStatusResponse getAgentTargetsStatus(String nodeId) {
        NodeAccessService.AccessibleNode node = access.require(nodeId);
        return agentTargets.status(node.host().assetId());
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

    /** 저장 경로의 검증 — 첫 위반을 400 으로 올린다. 규칙은 {@link #issues} 한 곳에만 둔다. */
    static void validate(CollectServiceRequest request) {
        List<Issue> issues = issues(request);
        if (!issues.isEmpty()) {
            throw bad(issues.get(0).message());
        }
        // 전체 비활성화도 저장 가능. 활성화 여부와 과거 로그 조회 권한은 독립적이다.
    }

    /** 입력 규칙 위반을 필드별로 모은다. field 는 요청 JSON 경로(위저드가 입력 옆에 매핑). */
    static List<Issue> issues(CollectServiceRequest request) {
        List<Issue> issues = new ArrayList<>();
        if (request == null) {
            issues.add(new Issue("request", "요청 본문이 필요합니다."));
            return issues;
        }
        if (request.serviceName() == null || request.serviceName().isBlank()
                || request.serviceName().trim().length() > MAX_NAME_LENGTH) {
            issues.add(new Issue("serviceName", "서비스 이름은 공백이 아닌 " + MAX_NAME_LENGTH + "자 이내입니다."));
        }
        if (request.description() != null && request.description().length() > MAX_DESCRIPTION_LENGTH) {
            issues.add(new Issue("description", "설명은 " + MAX_DESCRIPTION_LENGTH + "자 이내입니다."));
        }
        logIssues(request.logCollection(), issues);
        traceIssues(request.traceCollection(), issues);
        return issues;
    }

    private static void logIssues(CollectServiceRequest.LogCollection logs, List<Issue> issues) {
        if (logs == null) {
            return;
        }
        if (logs.syslogEnabled() && !logs.enabled()) {
            issues.add(new Issue("logCollection.syslogEnabled", "Syslog 설정은 로그 활성화가 필요합니다."));
        }
        if (logs.enabled() && logs.logPaths().isEmpty()) {
            issues.add(new Issue("logCollection.logPaths", "로그 활성화 시 경로가 필요합니다."));
        }
        if (logs.logPaths().size() > MAX_CHILD_ROWS) {
            issues.add(new Issue("logCollection.logPaths", "로그 경로는 최대 " + MAX_CHILD_ROWS + "개입니다."));
        }
        for (String path : logs.logPaths()) {
            if (!isAbsoluteFilePath(path)) {
                issues.add(new Issue("logCollection.logPaths",
                        "로그 경로는 glob 없는 절대 파일 경로(" + MAX_PATH_LENGTH + "자 이내)여야 합니다."));
                break;
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

    private static void traceIssues(CollectServiceRequest.TraceCollection traces, List<Issue> issues) {
        if (traces == null) {
            return;
        }
        if (traces.ports().size() > MAX_CHILD_ROWS) {
            issues.add(new Issue("traceCollection.ports", "트레이스 포트는 최대 " + MAX_CHILD_ROWS + "개입니다."));
        }
        if (traces.ports().stream().anyMatch(port -> port == null || port < 1 || port > 65535)) {
            issues.add(new Issue("traceCollection.ports", "포트는 1~65535입니다."));
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
