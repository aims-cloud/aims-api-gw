package com.innogrid.aims.monitoring.service.alarm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.innogrid.aims.auth.entity.User;
import com.innogrid.aims.auth.repository.UserRepository;
import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.common.util.SortSupport;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmChannel;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmMetricCatalogResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyDetailResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyListItemResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyRecipientResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyRequest;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyResourceResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyType;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmResourceType;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmRecipientCandidatesResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmRecipientCandidatesResponse.Member;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmRecipientCandidatesResponse.OrgNode;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmTargetScope;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmTargetType;
import com.innogrid.aims.monitoring.entity.AlarmPolicy;
import com.innogrid.aims.monitoring.entity.AlarmPolicy.RecipientRef;
import com.innogrid.aims.monitoring.log.PortalLogContextResolver;
import com.innogrid.aims.monitoring.repository.AlarmPolicyRepository;
import com.innogrid.aims.monitoring.repository.AlarmPolicySpecifications;
import com.innogrid.aims.monitoring.service.alarm.AlarmPolicyTargets.Target;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관측 > 알람 > 정책 탭 조회·관리 서비스 (docs/09 A.5.3 정책 원장).
 *
 * <ul>
 *   <li>역할 스코프: 목록·상세·편집·삭제 모두 토큰 역할의 정책만. 정책의 roleName 도 토큰 역할 안에서만 고를 수 있다(400).
 *       스코프 밖 정책은 존재를 드러내지 않기 위해 404.</li>
 *   <li>검증: 메트릭은 {@link AlarmMetric} 코드·별칭이며 자원 유형에 쓸 수 있어야 한다. INDIVIDUAL 은 대상 자원 1개 이상,
 *       채널·수신자는 1개 이상. 개인 수신자는 users.id, 조직 수신자는 users.organization 값이어야 한다. LOG 알람은 1차 제외(400).</li>
 *   <li>대상 자원 수·대상 자원 탭은 {@link AlarmPolicyTargets} 로 역할 스코프에서 전개한다(Tumblebug 장애 시 0·빈 목록).</li>
 *   <li>생성자는 인증 사용자 이메일로 저장하고 표시명(users.name)으로 응답한다.</li>
 * </ul>
 * 발생 이력 탭은 AlarmHistoryService(policyId 필터)를 재사용한다. 구 규칙 CRUD(/rules)·alarm_rules 는 제거됐다.
 */
@Service
@Transactional(readOnly = true)
public class AlarmPolicyService {

    private static final Logger log = LoggerFactory.getLogger(AlarmPolicyService.class);

    /** 목록 정렬 허용 필드(요청 이름) → 엔티티 필드 */
    private static final Map<String, String> SORT_FIELDS = Map.of(
            "policyName", "name", "roleName", "roleName", "areaId", "areaId", "resourceType", "resourceType",
            "level", "level", "createdBy", "createdBy", "createdAt", "createdAt");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");
    private static final Set<String> RESOURCE_SORT_FIELDS = Set.of("name", "alias", "resourceId");
    static final String UNASSIGNED_ORG = "미지정";
    static final String DELETED_USER = "(삭제된 사용자)";

    private final AlarmPolicyRepository repository;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final ResourceScopeService resourceScopeService;
    private final PortalLogContextResolver contextResolver;

    public AlarmPolicyService(AlarmPolicyRepository repository, UserRepository userRepository, AuthService authService,
                              ResourceScopeService resourceScopeService, PortalLogContextResolver contextResolver) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.authService = authService;
        this.resourceScopeService = resourceScopeService;
        this.contextResolver = contextResolver;
    }

    // ── 목록·상세 ─────────────────────────────────────────────────

    /** 정책 목록 — 역할·자원 영역·자원 유형·특정 자원·레벨·키워드 필터, 컬럼 정렬, 페이징(1부터).
     *  resourceId 는 자원 탐색기의 알람 탭에서 해당 자원이 대상에 포함된 정책만 조회할 때 사용한다. */
    public PageResponse<AlarmPolicyListItemResponse> getPolicies(
            String roleName, String areaId, String resourceType, String resourceId,
            AlarmLevel level, String keyword, int page, int size, String sort) {
        Sort order = translate(SortSupport.parse(sort, SORT_FIELDS.keySet(), DEFAULT_SORT)).and(Sort.by(Sort.Direction.DESC, "id"));
        Set<String> userRoles = authService.currentUserRoleIds();
        if (roleName != null && !roleName.isBlank() && !userRoles.contains(roleName.trim())) {
            return new PageResponse<>(List.of(), page, size, 0, 0);
        }

        Page<AlarmPolicy> result = repository.findAll(
                AlarmPolicySpecifications.filter(roleName, areaId, resourceType, resourceId, level, keyword, userRoles),
                PageRequest.of(page - 1, size, order));

        List<AlarmPolicy> policies = result.getContent();
        Map<Long, List<Target>> targets = targetsOf(policies);
        if (resourceId != null && !resourceId.isBlank()) {
            // ALL 정책은 실제로 그 자원을 덮는지 전개 결과로 확인 (INDIVIDUAL 은 원장 필터로 이미 확정)
            String rid = resourceId.trim();
            policies = policies.stream()
                    .filter(p -> p.getTargetScope() == AlarmTargetScope.INDIVIDUAL
                            || targets.getOrDefault(p.getId(), List.of()).stream().anyMatch(t -> t.resourceId().equals(rid)))
                    .toList();
        }
        Map<String, String> names = displayNames(policies.stream().map(AlarmPolicy::getCreatedBy).toList());
        List<AlarmPolicyListItemResponse> items = policies.stream()
                .map(p -> new AlarmPolicyListItemResponse(p.getId(), p.getName(), p.getRoleName(), p.getAreaId(),
                        p.getResourceType(), targets.getOrDefault(p.getId(), List.of()).size(), p.criterion(), p.getLevel(),
                        p.getChannels(), names.getOrDefault(p.getCreatedBy(), p.getCreatedBy()), p.getCreatedAt()))
                .toList();
        return new PageResponse<>(items, page, size, result.getTotalElements(), result.getTotalPages());
    }

    /** 정책 상세 — 하단 상세 탭·상세 페이지 상단 표·편집 폼 초기값 공용 */
    public AlarmPolicyDetailResponse getPolicy(Long policyId) {
        return detail(accessible(policyId));
    }

    // ── 생성·편집·복제·삭제 ───────────────────────────────────────

    /** 정책 생성 — 위저드 4단계(대상/조건/전송/요약) payload. 생성자는 인증 사용자 기준 기록 */
    @Transactional
    public AlarmPolicyDetailResponse createPolicy(AlarmPolicyRequest request) {
        Validated v = validate(request);
        AlarmPolicy policy = new AlarmPolicy(v.name, v.roleName, v.areaId, v.resourceType, v.targetScope, v.targetResourceIds,
                v.metric, request.condition().operator(), v.threshold, v.level, v.durationSeconds, v.channels, v.recipients,
                v.description, currentUserId());
        return detail(repository.save(policy));
    }

    /** 정책 편집 — 스코프 밖·없는 정책은 404 */
    @Transactional
    public AlarmPolicyDetailResponse updatePolicy(Long policyId, AlarmPolicyRequest request) {
        AlarmPolicy policy = accessible(policyId);
        Validated v = validate(request);
        policy.apply(v.name, v.roleName, v.areaId, v.resourceType, v.targetScope, v.targetResourceIds,
                v.metric, request.condition().operator(), v.threshold, v.level, v.durationSeconds, v.channels, v.recipients,
                v.description);
        return detail(repository.save(policy));
    }

    /** 정책 복제 — 원본 설정 복사본을 "{이름} (복제)" 로 생성. 복제자가 생성자 */
    @Transactional
    public AlarmPolicyDetailResponse duplicatePolicy(Long policyId) {
        AlarmPolicy source = accessible(policyId);
        String name = source.getName() + " (복제)";
        if (name.length() > 100) name = name.substring(0, 100);
        return detail(repository.save(source.copy(name, currentUserId())));
    }

    /** 정책 삭제 — 목록 체크박스 다중 선택. 하나라도 없거나 스코프 밖이면 404 로 전체 거부 */
    @Transactional
    public void deletePolicies(List<Long> policyIds) {
        if (policyIds == null || policyIds.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "삭제할 정책을 선택해 주세요");
        }
        List<AlarmPolicy> policies = policyIds.stream().distinct().map(this::accessible).toList();
        repository.deleteAll(policies);
    }

    // ── 하단 탭 ──────────────────────────────────────────────────

    /** 대상 자원 탭 — 정책이 적용되는 자원 목록(이름/별칭/ID), 키워드 필터·정렬(name|alias|resourceId)·페이징 */
    public PageResponse<AlarmPolicyResourceResponse> getPolicyResources(
            Long policyId, String keyword, int page, int size, String sort) {
        AlarmPolicy policy = accessible(policyId);
        Sort order = SortSupport.parse(sort, RESOURCE_SORT_FIELDS, Sort.by(Sort.Direction.ASC, "name"));
        Sort.Order o = order.iterator().next();

        List<AlarmPolicyResourceResponse> all = targetsOf(List.of(policy)).getOrDefault(policy.getId(), List.of()).stream()
                .filter(t -> matches(t, keyword))
                .map(t -> new AlarmPolicyResourceResponse(t.resourceId(), t.name(), t.alias()))
                .sorted(comparator(o))
                .toList();
        return PageResponse.of(all, page, size);
    }

    /** 전송 대상 탭 — 정책의 수신자 목록(이름/유형). 삭제된 사용자는 "(삭제된 사용자)" */
    public List<AlarmPolicyRecipientResponse> getPolicyRecipients(Long policyId) {
        return recipientsOf(accessible(policyId));
    }

    // ── 위저드 보조 ───────────────────────────────────────────────

    /**
     * 위저드 조건 설정 — 유형별 메트릭 카탈로그 ({@link AlarmMetric} 코드 상수, 미리보기 차트는 자원 탐색기 trend API 재사용).
     * resourceType(node|vm|gpu, 대소문자 무관)이 있으면 그 자원 유형에 쓸 수 있는 메트릭만, 없으면 전체. 모르는 유형은 400.
     */
    public List<AlarmMetricCatalogResponse> getMetricCatalog(String resourceType) {
        String type = normalizeResourceType(resourceType, false);
        return AlarmMetric.catalog(type);
    }

    /**
     * 위저드 전송 설정 — 수신자 후보. 조직 원장이 없어 users.organization 문자열을 1단계 조직으로 삼는다(하위 조직 없음).
     * 조직이 없는 사용자는 "미지정" 조직에 묶인다. keyword 는 조직명·사용자 이름·이메일 부분 일치(조직이 맞으면 멤버 전체).
     */
    public AlarmRecipientCandidatesResponse getRecipientCandidates(String keyword) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        Map<String, List<Member>> byOrg = new LinkedHashMap<>();
        for (User u : userRepository.findAll(Sort.by("organization", "name"))) {
            String org = organizationOf(u);
            boolean orgHit = kw.isEmpty() || org.toLowerCase(Locale.ROOT).contains(kw);
            boolean userHit = kw.isEmpty()
                    || (u.getName() != null && u.getName().toLowerCase(Locale.ROOT).contains(kw))
                    || (u.getEmail() != null && u.getEmail().toLowerCase(Locale.ROOT).contains(kw));
            if (!orgHit && !userHit) continue;
            byOrg.computeIfAbsent(org, k -> new ArrayList<>()).add(new Member(String.valueOf(u.getId()), u.getName()));
        }
        return new AlarmRecipientCandidatesResponse(byOrg.entrySet().stream()
                .map(e -> new OrgNode(e.getKey(), e.getKey(), List.of(), e.getValue()))
                .toList());
    }

    // ── 검증 ─────────────────────────────────────────────────────

    /** 검증이 끝난 요청 값 */
    record Validated(String name, String roleName, String areaId, String resourceType, AlarmTargetScope targetScope,
                     Set<String> targetResourceIds, AlarmMetric metric, double threshold, AlarmLevel level,
                     int durationSeconds, List<AlarmChannel> channels, List<RecipientRef> recipients, String description) {}

    Validated validate(AlarmPolicyRequest req) {
        if (req == null) throw badRequest("요청 본문이 없습니다");
        if (req.alarmType() == AlarmPolicyType.LOG) {
            throw badRequest("로그 알람은 1차 범위에서 제외되어 아직 만들 수 없습니다");
        }
        String name = required(req.policyName(), "정책명");
        if (name.length() > 100) throw badRequest("정책명은 100자 이하여야 합니다");

        String roleName = required(req.roleName(), "역할");
        if (!authService.currentUserRoleIds().contains(roleName)) {
            throw badRequest("현재 사용자에게 없는 역할입니다: " + roleName);
        }
        String areaId = req.areaId() == null || req.areaId().isBlank() ? null : req.areaId().trim();
        String resourceType = normalizeResourceType(req.resourceType(), true);

        AlarmTargetScope scope = req.targetScope();
        if (scope == null) throw badRequest("대상 범위(targetScope)를 선택해 주세요");
        Set<String> targetIds = new LinkedHashSet<>();
        if (scope == AlarmTargetScope.INDIVIDUAL) {
            req.targetResourceIds().stream().filter(s -> s != null && !s.isBlank()).map(String::trim).forEach(targetIds::add);
            if (targetIds.isEmpty()) throw badRequest("개별 대상은 자원을 1개 이상 선택해야 합니다");
        }

        AlarmPolicyRequest.Condition c = req.condition();
        if (c == null) throw badRequest("조건(condition)이 없습니다");
        AlarmMetric metric = AlarmMetric.resolve(c.metric())
                .orElseThrow(() -> badRequest("카탈로그에 없는 메트릭입니다: " + c.metric()));
        if (!metric.appliesTo(resourceType)) {
            throw badRequest(metric.name() + " 은(는) 자원 유형 " + resourceType + " 에 쓸 수 없습니다");
        }
        if (c.operator() == null) throw badRequest("조건 연산자(operator)를 선택해 주세요");
        if (c.threshold() == null || c.threshold().isNaN() || c.threshold().isInfinite()) {
            throw badRequest("임계값(threshold)은 숫자여야 합니다");
        }
        if (c.level() == null) throw badRequest("알람 레벨(level)을 선택해 주세요");

        AlarmPolicyRequest.Delivery d = req.delivery();
        if (d == null) throw badRequest("전송 설정(delivery)이 없습니다");
        if (d.durationSeconds() < 0) throw badRequest("조건 지속 시간은 0 이상이어야 합니다");
        List<AlarmChannel> channels = d.channels().stream().filter(ch -> ch != null).distinct().toList();
        if (channels.isEmpty()) throw badRequest("알림 방식을 1개 이상 선택해 주세요");
        List<RecipientRef> recipients = validateRecipients(d.recipients());

        String description = req.description() == null || req.description().isBlank() ? null : req.description().trim();
        if (description != null && description.length() > 1000) throw badRequest("설명은 1000자 이하여야 합니다");

        return new Validated(name, roleName, areaId, resourceType, scope, targetIds, metric, c.threshold(), c.level(),
                d.durationSeconds(), channels, recipients, description);
    }

    /** 개인은 users.id 가 존재해야, 조직은 users.organization 에 있는 값이어야 한다 */
    private List<RecipientRef> validateRecipients(List<AlarmPolicyRequest.Recipient> requested) {
        List<RecipientRef> refs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AlarmPolicyRequest.Recipient r : requested) {
            if (r == null || r.targetType() == null || r.targetId() == null || r.targetId().isBlank()) {
                throw badRequest("수신자는 유형(targetType)과 대상(targetId)이 있어야 합니다");
            }
            String id = r.targetId().trim();
            if (!seen.add(r.targetType() + ":" + id)) continue;
            if (r.targetType() == AlarmTargetType.INDIVIDUAL) {
                Long userId = parseLong(id).orElseThrow(() -> badRequest("개인 수신자 id 는 사용자 번호여야 합니다: " + id));
                if (!userRepository.existsById(userId)) throw badRequest("존재하지 않는 사용자입니다: " + id);
            } else if (!organizationExists(id)) {
                throw badRequest("존재하지 않는 조직입니다: " + id);
            }
            refs.add(new RecipientRef(r.targetType(), id));
        }
        if (refs.isEmpty()) throw badRequest("수신자를 1명 이상 선택해 주세요");
        return refs;
    }

    private boolean organizationExists(String organization) {
        if (UNASSIGNED_ORG.equals(organization)) {
            return userRepository.findAll().stream().anyMatch(u -> u.getOrganization() == null || u.getOrganization().isBlank());
        }
        return userRepository.findAll().stream().anyMatch(u -> organization.equals(u.getOrganization()));
    }

    // ── 내부 ─────────────────────────────────────────────────────

    /** 존재하고 토큰 역할 스코프 안인 정책 — 아니면 404 */
    private AlarmPolicy accessible(Long policyId) {
        AlarmPolicy policy = repository.findById(policyId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "정책을 찾을 수 없습니다: " + policyId));
        if (!authService.currentUserRoleIds().contains(policy.getRoleName())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "정책을 찾을 수 없습니다: " + policyId);
        }
        return policy;
    }

    private String currentUserId() {
        String userId = contextResolver.resolve().userId();
        if (userId == null) throw new BusinessException(HttpStatus.UNAUTHORIZED, "사용자를 확인할 수 없습니다");
        return userId;
    }

    private AlarmPolicyDetailResponse detail(AlarmPolicy p) {
        String createdBy = displayNames(List.of(p.getCreatedBy())).getOrDefault(p.getCreatedBy(), p.getCreatedBy());
        return new AlarmPolicyDetailResponse(p.getId(), p.getType(), p.getName(), p.getRoleName(), p.getAreaId(), p.getAreaKind(),
                p.getResourceType(), p.getTargetScope(), List.copyOf(p.getTargetResourceIds()),
                p.alarmMetric().type().label(), p.getMetric(), p.getOperator(), p.getThreshold(), p.criterion(), p.getLevel(),
                p.getChannels(), p.getDurationSeconds(), recipientsOf(p), p.isEnabled(), createdBy,
                p.getCreatedAt(), p.getUpdatedAt(), p.getDescription());
    }

    private List<AlarmPolicyRecipientResponse> recipientsOf(AlarmPolicy p) {
        Set<Long> userIds = p.getRecipients().stream()
                .filter(r -> r.targetType() == AlarmTargetType.INDIVIDUAL)
                .map(r -> parseLong(r.targetId())).flatMap(Optional::stream)
                .collect(Collectors.toSet());
        Map<Long, String> userNames = userIds.isEmpty() ? Map.of()
                : userRepository.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, User::getName));
        return p.getRecipients().stream()
                .map(r -> new AlarmPolicyRecipientResponse(r.targetType(), r.targetId(),
                        r.targetType() == AlarmTargetType.ORGANIZATION ? r.targetId()
                                : parseLong(r.targetId()).map(userNames::get).orElse(DELETED_USER)))
                .toList();
    }

    /** 정책별 대상 자원 전개 — 역할 스코프는 페이지의 역할 전체를 한 번에 읽는다. 스코프 조회 실패 시 전부 빈 목록 */
    private Map<Long, List<Target>> targetsOf(Collection<AlarmPolicy> policies) {
        if (policies.isEmpty()) return Map.of();
        Set<String> roles = policies.stream().map(AlarmPolicy::getRoleName).collect(Collectors.toCollection(LinkedHashSet::new));
        Scope scope;
        try {
            scope = resourceScopeService.scopeOf(roles);
        } catch (RuntimeException e) {
            log.warn("정책 대상 자원 전개 실패 — 역할 스코프 조회 오류: roles={}, error={}", roles, e.getMessage());
            return Map.of();
        }
        Map<Long, List<Target>> result = new HashMap<>();
        for (AlarmPolicy p : policies) result.put(p.getId(), AlarmPolicyTargets.resolve(p, scope));
        return result;
    }

    /** 생성자 이메일 → users.name. 없는 사용자는 이메일 그대로 */
    private Map<String, String> displayNames(Collection<String> emails) {
        Set<String> set = emails.stream().filter(e -> e != null && !e.isBlank()).collect(Collectors.toSet());
        if (set.isEmpty()) return Map.of();
        return userRepository.findByEmailIn(set).stream()
                .filter(u -> u.getName() != null)
                .collect(Collectors.toMap(User::getEmail, User::getName, (a, b) -> a));
    }

    private static Sort translate(Sort requested) {
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order o : requested) {
            orders.add(new Sort.Order(o.getDirection(), SORT_FIELDS.getOrDefault(o.getProperty(), o.getProperty())));
        }
        return Sort.by(orders);
    }

    private static Comparator<AlarmPolicyResourceResponse> comparator(Sort.Order o) {
        Function<AlarmPolicyResourceResponse, String> key = switch (o.getProperty()) {
            case "alias" -> AlarmPolicyResourceResponse::alias;
            case "resourceId" -> AlarmPolicyResourceResponse::resourceId;
            default -> AlarmPolicyResourceResponse::name;
        };
        Comparator<AlarmPolicyResourceResponse> c = Comparator.comparing(key, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
        return o.isDescending() ? c.reversed() : c;
    }

    private static boolean matches(Target t, String keyword) {
        if (keyword == null || keyword.isBlank()) return true;
        String kw = keyword.trim().toLowerCase(Locale.ROOT);
        return contains(t.name(), kw) || contains(t.alias(), kw) || contains(t.resourceId(), kw);
    }

    private static boolean contains(String s, String kw) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(kw);
    }

    static String organizationOf(User u) {
        return u.getOrganization() == null || u.getOrganization().isBlank() ? UNASSIGNED_ORG : u.getOrganization().trim();
    }

    /** {@link AlarmResourceType} 코드(node|vm|gpu)로 정규화. required 가 false 면 빈 값은 null */
    private static String normalizeResourceType(String resourceType, boolean required) {
        if (resourceType == null || resourceType.isBlank()) {
            if (required) throw badRequest("자원 유형(resourceType)을 선택해 주세요");
            return null;
        }
        return AlarmResourceType.fromCode(resourceType)
                .map(AlarmResourceType::code)
                .orElseThrow(() -> badRequest("지원하지 않는 자원 유형입니다: " + resourceType + " (node, vm, gpu)"));
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw badRequest(label + "을(를) 입력해 주세요");
        return value.trim();
    }

    private static Optional<Long> parseLong(String s) {
        try {
            return Optional.of(Long.parseLong(s.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }
}
