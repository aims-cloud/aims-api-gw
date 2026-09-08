package com.innogrid.aims.monitoring.service.alarm;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.List;

import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmAcknowledgeRequest;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmAcknowledgementResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmHistoryListItemResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmHistoryStatus;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmHistorySearchField;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmSource;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleAreaListItemResponse.AreaKind;
import com.innogrid.aims.monitoring.entity.AlarmHistory;
import com.innogrid.aims.monitoring.log.PortalLogContext;
import com.innogrid.aims.monitoring.log.PortalLogContextResolver;
import com.innogrid.aims.monitoring.log.PortalLogService;
import com.innogrid.aims.monitoring.repository.AlarmHistoryRepository;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 알람 발생 이력 목록 서비스 — 역할 스코프(토큰 역할 + 역할 없는 행), 스코프 밖 roleName 은 빈 결과, 관측값 표기,
 * 영역 표시명(MDC 는 id, Namespace 는 Tumblebug 표시명), 날짜 기간(KST 양끝 포함), 검색 대상, 정렬·페이징, 400,
 * 확인(acknowledge)·확인 내역(404·409·400·포털 로그 기록). 원장은 실제 H2, 인증·Tumblebug·포털 로그는 대역.
 */
@SpringBootTest
@Transactional
class AlarmHistoryServiceTest {

    static final OffsetDateTime BASE = OffsetDateTime.of(2026, 9, 8, 9, 0, 0, 0, ZoneOffset.ofHours(9));

    @Autowired
    AlarmHistoryRepository repository;
    @MockitoBean
    AuthService authService;
    @MockitoBean
    TumblebugTreeBuilder tumblebugTreeBuilder;
    @MockitoBean
    PortalLogContextResolver contextResolver;
    @MockitoBean
    PortalLogService portalLogService;
    AlarmHistoryService service;
    Long openId;
    Long ackedId;
    Long userScopedId;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        service = new AlarmHistoryService(repository, authService, tumblebugTreeBuilder, contextResolver, portalLogService,
                ZoneId.of("Asia/Seoul"));
        when(authService.currentUserRoleIds()).thenReturn(new LinkedHashSet<>(List.of("ADMIN")));
        when(contextResolver.resolve()).thenReturn(new PortalLogContext("admin@innogrid.com", "관리자", "10.0.0.9", "req-1", "POST", "/x"));
        when(tumblebugTreeBuilder.snapshot(anyCollection())).thenReturn(new TbSnapshot(Instant.now(),
                Map.of("openstack", "OpenStack 테스트베드"), Map.of(), Set.of("openstack"), Set.of(), false));
        openId = repository.save(AlarmHistory.builder(AlarmSource.POLICY, AlarmLevel.CRITICAL, "CPU Used > 80%")
                .policy(1L, "cpu-high").resource("node", "asset-1", "node-01").role("ADMIN").mdc("mdc-01")
                .observed(92.0, "%").occurredAt(BASE).build()).getId();
        userScopedId = repository.save(AlarmHistory.builder(AlarmSource.POLICY, AlarmLevel.WARNING, "Memory Used > 70%")
                .policy(2L, "mem-high").resource("vm", "TB:openstack/openstack-infra-01/vm-1", "vm-1").role("USER").namespace("openstack")
                .observed(3.25, "GB").occurredAt(BASE.plusMinutes(30)).build()).getId();
        repository.save(AlarmHistory.builder(AlarmSource.SYSTEM, AlarmLevel.WARNING, "up == 0")
                .resource("node", "asset-2", "node-02").mdc("mdc-01").occurredAt(BASE.plusHours(1)).build());
        AlarmHistory acked = repository.save(AlarmHistory.builder(AlarmSource.POLICY, AlarmLevel.CRITICAL, "GPU Temp > 85°C")
                .policy(3L, "gpu-hot").resource("gpu", "asset-1/gpu-0", "node-01 GPU0").role("ADMIN").mdc("mdc-01")
                .observed(88.0, "°C").occurredAt(BASE.plusHours(2)).inhibited(true).build());
        acked.acknowledge("admin@innogrid.com", "확인함", BASE.plusHours(2).plusMinutes(1));
        ackedId = acked.getId();
    }

    @Test
    void 토큰_역할_행과_역할_없는_행만_최신순으로_내려온다() {
        PageResponse<AlarmHistoryListItemResponse> page = service.getHistories(null, null, null, null, null, null, null, null,
                null, null, 1, 10, null);

        assertThat(page.totalElements()).isEqualTo(3);                              // USER 역할 행 제외
        assertThat(page.items()).extracting(AlarmHistoryListItemResponse::criterion)
                .containsExactly("GPU Temp > 85°C", "up == 0", "CPU Used > 80%");
        AlarmHistoryListItemResponse gpu = page.items().get(0);
        assertThat(gpu.observedValue()).isEqualTo("88°C");
        assertThat(gpu.acknowledged()).isTrue();
        assertThat(gpu.inhibited()).isTrue();
        assertThat(gpu.source()).isEqualTo(AlarmSource.POLICY);
        assertThat(page.items().get(1).roleName()).isNull();                        // SYSTEM 은 역할 없음
        assertThat(page.items().get(1).areaId()).isEqualTo("mdc-01");
        assertThat(page.items().get(1).areaKind()).isEqualTo(AreaKind.MDC);
        assertThat(page.items().get(1).areaName()).isEqualTo("mdc-01");
        assertThat(page.items().get(2).observedValue()).isEqualTo("92%");
        assertThat(page.items().get(2).deliveryCount()).isZero();
    }

    @Test
    void 토큰에_없는_역할을_지정하면_빈_결과다() {
        PageResponse<AlarmHistoryListItemResponse> page = service.getHistories(null, "USER", null, null, null, null, null, null,
                null, null, 1, 10, null);

        assertThat(page.totalElements()).isZero();
        assertThat(page.items()).isEmpty();
    }

    @Test
    void 관측값_표기는_단위에_따라_붙여쓰기와_소수_처리가_다르다() {
        when(authService.currentUserRoleIds()).thenReturn(new LinkedHashSet<>(List.of("ADMIN", "USER")));

        PageResponse<AlarmHistoryListItemResponse> page = service.getHistories(null, null, null, null, null, null, AlarmHistorySearchField.POLICY_NAME, "mem-high",
                null, null, 1, 10, null);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).observedValue()).isEqualTo("3.3 GB");
        assertThat(page.items().get(0).areaId()).isEqualTo("TB:openstack");
        assertThat(page.items().get(0).areaKind()).isEqualTo(AreaKind.NAMESPACE);
        assertThat(page.items().get(0).areaName()).isEqualTo("OpenStack 테스트베드");
        assertThat(AlarmHistoryListItemResponse.formatObserved(75.5, "%")).isEqualTo("75.5%");
        assertThat(AlarmHistoryListItemResponse.formatObserved(null, "%")).isNull();
        assertThat(AlarmHistoryListItemResponse.formatObserved(7.0, null)).isEqualTo("7");
    }

    @Test
    void 정렬_페이징과_필터_조합() {
        PageResponse<AlarmHistoryListItemResponse> asc = service.getHistories(null, null, null, null, null, null, null, null,
                null, null, 1, 2, "occurredAt,asc");
        assertThat(asc.totalPages()).isEqualTo(2);
        assertThat(asc.items().get(0).criterion()).isEqualTo("CPU Used > 80%");

        PageResponse<AlarmHistoryListItemResponse> critical = service.getHistories(List.of(AlarmLevel.CRITICAL), null, "mdc-01",
                "node", null, null, null, null, null, null, 1, 10, null);
        assertThat(critical.totalElements()).isEqualTo(1);

        PageResponse<AlarmHistoryListItemResponse> byResource = service.getHistories(null, null, null, null, "asset-2", null, null, null,
                null, null, 1, 10, null);
        assertThat(byResource.items()).extracting(AlarmHistoryListItemResponse::resourceName).containsExactly("node-02");
    }

    @Test
    void 날짜_기간은_KST_양끝_포함이고_한쪽만_지정할_수_있다() {
        // 09-08 09:00 / 09:30(USER) / 10:00 / 11:00 KST — 전부 09-08. 09-07 만 → 0건, 09-08 부터 → 3건(스코프 ADMIN)
        assertThat(service.getHistories(null, null, null, null, null, null, null, null,
                LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 7), 1, 10, null).totalElements()).isZero();
        assertThat(service.getHistories(null, null, null, null, null, null, null, null,
                LocalDate.of(2026, 9, 8), null, 1, 10, null).totalElements()).isEqualTo(3);
        assertThat(service.getHistories(null, null, null, null, null, null, null, null,
                null, LocalDate.of(2026, 9, 8), 1, 10, null).totalElements()).isEqualTo(3);
    }

    @Test
    void 검색_대상_ALL은_자원명과_정책명만_본다() {
        assertThat(service.getHistories(null, null, null, null, null, null, AlarmHistorySearchField.ALL, "node-01",
                null, null, 1, 10, null).totalElements()).isEqualTo(2);
        assertThat(service.getHistories(null, null, null, null, null, null, AlarmHistorySearchField.RESOURCE_NAME, "cpu-high",
                null, null, 1, 10, null).totalElements()).isZero();
        assertThat(service.getHistories(null, null, null, null, null, null, null, "CPU Used",              // 기준은 검색 대상 아님
                null, null, 1, 10, null).totalElements()).isZero();
    }

    @Test
    void Tumblebug_조회가_실패해도_Namespace_표시명은_nsId로_대체된다() {
        when(authService.currentUserRoleIds()).thenReturn(new LinkedHashSet<>(List.of("ADMIN", "USER")));
        when(tumblebugTreeBuilder.snapshot(anyCollection())).thenThrow(new IllegalStateException("tumblebug down"));

        PageResponse<AlarmHistoryListItemResponse> page = service.getHistories(null, "USER", null, null, null, null, null, null,
                null, null, 1, 10, null);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).areaName()).isEqualTo("openstack");
    }

    // ── 확인 ────────────────────────────────────────────────────

    @Test
    void 확인하면_확인자_시각_내용이_저장되고_상태가_바뀌고_포털_로그가_남는다() {
        AlarmAcknowledgementResponse res = service.acknowledge(openId, new AlarmAcknowledgeRequest("  재기동 예정  "));

        assertThat(res.acknowledgedBy()).isEqualTo("admin@innogrid.com");
        assertThat(res.acknowledgedAt()).isNotNull();
        assertThat(res.content()).isEqualTo("재기동 예정");
        assertThat(res.criterion()).isEqualTo("CPU Used > 80%");
        assertThat(res.observedValue()).isEqualTo("92%");
        assertThat(res.resourceName()).isEqualTo("node-01");

        AlarmHistory saved = repository.findById(openId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(AlarmHistoryStatus.ACKNOWLEDGED);
        assertThat(saved.isAcknowledged()).isTrue();
        verify(portalLogService).alarm(eq("확인"), eq("CPU Used > 80% 확인: node-01"), eq(openId));

        // 확인 내역 조회는 같은 값을 돌려준다
        assertThat(service.getAcknowledgement(openId).content()).isEqualTo("재기동 예정");
    }

    @Test
    void 빈_확인_내용은_400이고_저장하지_않는다() {
        assertThatThrownBy(() -> service.acknowledge(openId, new AlarmAcknowledgeRequest("   ")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.acknowledge(openId, null)).isInstanceOf(BusinessException.class);

        assertThat(repository.findById(openId).orElseThrow().isAcknowledged()).isFalse();
        verify(portalLogService, never()).alarm(anyString(), anyString(), anyLong());
    }

    @Test
    void 이미_확인된_알람은_409다() {
        assertThatThrownBy(() -> service.acknowledge(ackedId, new AlarmAcknowledgeRequest("다시")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(repository.findById(ackedId).orElseThrow().getAckContent()).isEqualTo("확인함");
    }

    @Test
    void 없는_알람과_스코프_밖_알람은_404이고_미확인_알람의_확인_내역도_404다() {
        assertThatThrownBy(() -> service.acknowledge(999_999L, new AlarmAcknowledgeRequest("x")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.acknowledge(userScopedId, new AlarmAcknowledgeRequest("x")))   // USER 역할 행, 토큰은 ADMIN
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getAcknowledgement(openId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(portalLogService, never()).alarm(anyString(), anyString(), anyLong());
    }

    @Test
    void 인증_사용자를_알_수_없으면_401이다() {
        when(contextResolver.resolve()).thenReturn(PortalLogContext.EMPTY);

        assertThatThrownBy(() -> service.acknowledge(openId, new AlarmAcknowledgeRequest("x")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void 허용하지_않는_정렬과_뒤집힌_기간은_400() {
        assertThatThrownBy(() -> service.getHistories(null, null, null, null, null, null, null, null, null, null, 1, 10, "ackContent,asc"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.getHistories(null, null, null, null, null, null, null, null, LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 8), 1, 10, null))
                .isInstanceOf(BusinessException.class);
    }
}
