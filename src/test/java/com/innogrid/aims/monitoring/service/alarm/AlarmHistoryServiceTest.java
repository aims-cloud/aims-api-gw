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
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmHistoryListItemResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmHistorySearchField;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmSource;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleAreaListItemResponse.AreaKind;
import com.innogrid.aims.monitoring.entity.AlarmHistory;
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
import static org.mockito.Mockito.when;

/**
 * 알람 발생 이력 목록 서비스 — 역할 스코프(토큰 역할 + 역할 없는 행), 스코프 밖 roleName 은 빈 결과, 관측값 표기,
 * 영역 표시명(MDC 는 id, Namespace 는 Tumblebug 표시명), 날짜 기간(KST 양끝 포함), 검색 대상, 정렬·페이징, 400.
 * 원장은 실제 H2, 인증·Tumblebug 은 대역.
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
    AlarmHistoryService service;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        service = new AlarmHistoryService(repository, authService, tumblebugTreeBuilder, ZoneId.of("Asia/Seoul"));
        when(authService.currentUserRoleIds()).thenReturn(new LinkedHashSet<>(List.of("ADMIN")));
        when(tumblebugTreeBuilder.snapshot(anyCollection())).thenReturn(new TbSnapshot(Instant.now(),
                Map.of("openstack", "OpenStack 테스트베드"), Map.of(), Set.of("openstack"), Set.of(), false));
        repository.save(AlarmHistory.builder(AlarmSource.POLICY, AlarmLevel.CRITICAL, "CPU Used > 80%")
                .policy(1L, "cpu-high").resource("node", "asset-1", "node-01").role("ADMIN").mdc("mdc-01")
                .observed(92.0, "%").occurredAt(BASE).build());
        repository.save(AlarmHistory.builder(AlarmSource.POLICY, AlarmLevel.WARNING, "Memory Used > 70%")
                .policy(2L, "mem-high").resource("vm", "TB:openstack/openstack-infra-01/vm-1", "vm-1").role("USER").namespace("openstack")
                .observed(3.25, "GB").occurredAt(BASE.plusMinutes(30)).build());
        repository.save(AlarmHistory.builder(AlarmSource.SYSTEM, AlarmLevel.WARNING, "up == 0")
                .resource("node", "asset-2", "node-02").mdc("mdc-01").occurredAt(BASE.plusHours(1)).build());
        AlarmHistory acked = repository.save(AlarmHistory.builder(AlarmSource.POLICY, AlarmLevel.CRITICAL, "GPU Temp > 85°C")
                .policy(3L, "gpu-hot").resource("gpu", "asset-1/gpu-0", "node-01 GPU0").role("ADMIN").mdc("mdc-01")
                .observed(88.0, "°C").occurredAt(BASE.plusHours(2)).inhibited(true).build());
        acked.acknowledge("admin@innogrid.com", "확인함", BASE.plusHours(2).plusMinutes(1));
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

    @Test
    void 허용하지_않는_정렬과_뒤집힌_기간은_400() {
        assertThatThrownBy(() -> service.getHistories(null, null, null, null, null, null, null, null, null, null, 1, 10, "ackContent,asc"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.getHistories(null, null, null, null, null, null, null, null, LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 8), 1, 10, null))
                .isInstanceOf(BusinessException.class);
    }
}
