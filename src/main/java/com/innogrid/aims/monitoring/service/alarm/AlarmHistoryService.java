package com.innogrid.aims.monitoring.service.alarm;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.common.util.SortSupport;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmAcknowledgeRequest;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmAcknowledgementResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmDeliveryResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmHistoryListItemResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmHistorySearchField;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmMessageDetailResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmChannel;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleAreaListItemResponse.AreaKind;
import com.innogrid.aims.monitoring.entity.AlarmHistory;
import com.innogrid.aims.monitoring.log.PortalLogContextResolver;
import com.innogrid.aims.monitoring.log.PortalLogService;
import com.innogrid.aims.monitoring.repository.AlarmHistoryRepository;
import com.innogrid.aims.monitoring.repository.AlarmHistorySpecifications;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 관측 > 알람 > 발생 이력 탭 조회·확인 서비스 (docs/09).
 *
 * <ul>
 *   <li>목록: {@code alarm_history} 원장을 레벨·역할·자원 영역(areaId = mdcId | TB:{nsId})·자원 유형·특정 자원·정책·기간·키워드로 걸러 페이징한다.
 *       기간은 날짜(YYYY-MM-DD, 양끝 포함)로 받아 서버 타임존 {@code [from 00:00, to+1일 00:00)} 으로 바꾼다(프론트 DateField 와 동일).
 *       검색은 searchField(전체=자원명+정책명 / 자원명 / 정책명)로 좁힌다. 영역 표시명(areaName)은 MDC 는 mdcId 그대로,
 *       Namespace 는 Tumblebug 스냅샷의 표시명이다(조회 실패 시 nsId).</li>
 *   <li>역할 스코프: 토큰 역할에 속한 행 + 역할이 없는 행(내장 시스템 규칙·외부 알람)만 보인다.
 *       {@code roleName} 을 지정했는데 토큰 역할에 없으면 빈 결과다(자원 탐색기 NodeGroup 패널과 같은 규칙).</li>
 *   <li>확인(acknowledge): 확인 내용 필수, 확인자·시각은 인증 사용자 기준으로 서버가 기록한다. 이미 확인된 알람은 409.
 *       스코프 밖 알람은 존재를 드러내지 않기 위해 404. 확인 시 포털 로그 ALARM 행을 함께 남긴다(docs/08 §3.2).</li>
 *   <li>전송 이력·메시지 상세는 전송 원장 구현 시 채운다(현재 501).</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class AlarmHistoryService {

    static final Set<String> SORTABLE = Set.of(
            "occurredAt", "level", "source", "status", "resourceType", "resourceName", "criterion", "observedValue",
            "roleName", "areaId", "policyName", "deliveryCount");
    static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "occurredAt");

    private static final Logger log = LoggerFactory.getLogger(AlarmHistoryService.class);
    private static final String NAMESPACE_PREFIX = "TB:";

    private final AlarmHistoryRepository repository;
    private final AuthService authService;
    private final TumblebugTreeBuilder tumblebugTreeBuilder;
    private final PortalLogContextResolver contextResolver;
    private final PortalLogService portalLogService;
    private final ZoneId zone;

    @Autowired
    public AlarmHistoryService(AlarmHistoryRepository repository, AuthService authService,
                               TumblebugTreeBuilder tumblebugTreeBuilder,
                               PortalLogContextResolver contextResolver, PortalLogService portalLogService) {
        this(repository, authService, tumblebugTreeBuilder, contextResolver, portalLogService, ZoneId.systemDefault());
    }

    /** 테스트용 — 기간 변환 타임존 고정 */
    AlarmHistoryService(AlarmHistoryRepository repository, AuthService authService,
                        TumblebugTreeBuilder tumblebugTreeBuilder,
                        PortalLogContextResolver contextResolver, PortalLogService portalLogService, ZoneId zone) {
        this.repository = repository;
        this.authService = authService;
        this.tumblebugTreeBuilder = tumblebugTreeBuilder;
        this.contextResolver = contextResolver;
        this.portalLogService = portalLogService;
        this.zone = zone;
    }

    /** 발생 이력 목록 — 레벨(다중)·역할·자원 영역·자원 유형·특정 자원·정책·기간·키워드 필터, 컬럼 정렬, 페이징(1부터).
     *  resourceId 는 자원 탐색기 알람 탭(선택 자원 스코프), policyId 는 정책 탭 발생 이력에서 쓴다. */
    public PageResponse<AlarmHistoryListItemResponse> getHistories(
            List<AlarmLevel> levels, String roleName, String areaId, String resourceType,
            String resourceId, Long policyId, AlarmHistorySearchField searchField, String keyword,
            LocalDate from, LocalDate to, int page, int size, String sort) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "시작일은 종료일보다 늦을 수 없습니다: " + from + " > " + to);
        }
        Sort order = SortSupport.parse(sort, SORTABLE, DEFAULT_SORT).and(Sort.by(Sort.Direction.DESC, "id"));

        Set<String> userRoles = authService.currentUserRoleIds();
        if (roleName != null && !roleName.isBlank() && !userRoles.contains(roleName.trim())) {
            return new PageResponse<>(List.of(), page, size, 0, 0);          // 토큰에 없는 역할 — 스코프 밖
        }

        Page<AlarmHistory> result = repository.findAll(
                AlarmHistorySpecifications.filter(levels, roleName, areaId, resourceType, resourceId, policyId,
                        searchField, keyword, startOf(from), endOf(to), userRoles),
                PageRequest.of(page - 1, size, order));
        Map<String, String> areaNames = areaNames(result.getContent());
        return new PageResponse<>(
                result.getContent().stream().map(e -> AlarmHistoryListItemResponse.from(e, areaNames.get(e.getAreaId()))).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    /** 페이지에 등장한 영역의 표시명 — MDC 는 mdcId, Namespace 는 Tumblebug 표시명(스냅샷 실패·부재 시 nsId) */
    Map<String, String> areaNames(List<AlarmHistory> rows) {
        Map<String, String> names = new HashMap<>();
        Set<String> nsIds = rows.stream()
                .filter(r -> r.getAreaKind() == AreaKind.NAMESPACE && r.getAreaId() != null && r.getAreaId().startsWith(NAMESPACE_PREFIX))
                .map(r -> r.getAreaId().substring(NAMESPACE_PREFIX.length()))
                .collect(Collectors.toSet());
        Map<String, String> tbNames = Map.of();
        if (!nsIds.isEmpty()) {
            try {
                tbNames = tumblebugTreeBuilder.snapshot(nsIds).namespaceNames();
            } catch (RuntimeException e) {
                log.warn("Tumblebug Namespace 표시명 조회 실패 — nsId 로 표기합니다: {}", e.getMessage());
            }
        }
        for (AlarmHistory r : rows) {
            if (r.getAreaId() == null) {
                continue;
            }
            if (r.getAreaKind() == AreaKind.NAMESPACE && r.getAreaId().startsWith(NAMESPACE_PREFIX)) {
                String nsId = r.getAreaId().substring(NAMESPACE_PREFIX.length());
                names.put(r.getAreaId(), tbNames.getOrDefault(nsId, nsId));
            } else {
                names.put(r.getAreaId(), r.getAreaId());
            }
        }
        return names;
    }

    OffsetDateTime startOf(LocalDate date) {
        return date == null ? null : date.atStartOfDay(zone).toOffsetDateTime();
    }

    OffsetDateTime endOf(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
    }

    /** 전송 이력 (행 확장) — 대상별 채널 전송 결과 (개인: 단일 상태, 조직: 카운트) */
    public List<AlarmDeliveryResponse> getDeliveries(Long alarmId) {
        // TODO:: 전송 원장(alarm_deliveries·alarm_delivery_recipients) 구현 후 (docs/09 A.5.4)
        throw dataSourceNotImplemented();
    }

    /** 메시지 상세 모달 — 발송 메시지 본문과 전송 대상 목록(수신 주소·결과·실패 사유·전송 일시) */
    public AlarmMessageDetailResponse getMessageDetail(Long alarmId, Long deliveryId, AlarmChannel channel) {
        // TODO:: 전송 원장 구현 후 — 본문은 alarm_deliveries.body 스냅샷
        throw dataSourceNotImplemented();
    }

    /** 알람 확인 — 확인 내용(필수) 저장, 확인자·확인 시각은 인증 사용자 기준으로 서버가 기록. 이미 확인됐으면 409 */
    @Transactional
    public AlarmAcknowledgementResponse acknowledge(Long alarmId, AlarmAcknowledgeRequest request) {
        String content = request == null ? null : request.content();
        if (content == null || content.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "확인 내용을 입력해 주세요");
        }
        String userId = contextResolver.resolve().userId();
        if (userId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "확인자를 확인할 수 없습니다");
        }
        AlarmHistory alarm = accessibleAlarm(alarmId);
        if (alarm.isAcknowledged()) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 확인된 알람입니다: " + alarmId);
        }
        alarm.acknowledge(userId, content.trim(), OffsetDateTime.now());
        portalLogService.alarm("확인", alarm.getCriterion() + " 확인: " + alarm.getResourceName(), alarm.getId());
        return toAcknowledgement(alarm);
    }

    /** 알람 확인 내역 모달 — 발생 정보 + 확인 일시/확인자/확인 내용. 미확인 알람이면 404 */
    public AlarmAcknowledgementResponse getAcknowledgement(Long alarmId) {
        AlarmHistory alarm = accessibleAlarm(alarmId);
        if (!alarm.isAcknowledged()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "확인 내역이 없는 알람입니다: " + alarmId);
        }
        return toAcknowledgement(alarm);
    }

    /** 존재하고 토큰 역할 스코프 안(역할 없는 행 포함)인 알람 — 아니면 404 (스코프 밖 알람의 존재를 드러내지 않는다) */
    private AlarmHistory accessibleAlarm(Long alarmId) {
        AlarmHistory alarm = repository.findById(alarmId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "알람을 찾을 수 없습니다: " + alarmId));
        if (alarm.getRoleName() != null && !authService.currentUserRoleIds().contains(alarm.getRoleName())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "알람을 찾을 수 없습니다: " + alarmId);
        }
        return alarm;
    }

    private static AlarmAcknowledgementResponse toAcknowledgement(AlarmHistory a) {
        return new AlarmAcknowledgementResponse(a.getOccurredAt(), a.getLevel(), a.getResourceType(), a.getResourceName(),
                a.getCriterion(), AlarmHistoryListItemResponse.formatObserved(a.getObservedValue(), a.getUnit()),
                a.getAcknowledgedAt(), a.getAcknowledgedBy(), a.getAckContent());
    }

    private ResponseStatusException dataSourceNotImplemented() {
        return new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "알림 전송 이력·확인 데이터와 알람 발생 이력 화면의 매핑 구현 전입니다.");
    }
}
